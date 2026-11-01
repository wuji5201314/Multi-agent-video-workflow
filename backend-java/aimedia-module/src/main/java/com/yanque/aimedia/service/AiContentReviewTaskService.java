package com.yanque.aimedia.service;

import com.yanque.aimedia.common.AiMediaErrors;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.aimedia.config.AiContentPreviewProperties;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.mapper.AiContentReviewIssueMapper;
import com.yanque.aimedia.mapper.AiContentReviewTaskMapper;
import com.yanque.aimedia.mapper.AiContentPreviewTaskMapper;
import com.yanque.aimedia.pojo.entity.AiContentReviewIssueEntity;
import com.yanque.aimedia.pojo.entity.AiContentReviewTaskEntity;
import com.yanque.aimedia.pojo.vo.resvo.ContentReviewIssueRes;
import com.yanque.aimedia.pojo.vo.resvo.ContentReviewTaskRes;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 保存审核任务、分数和问题明细。 */
@Service
public class AiContentReviewTaskService {
    private final AiContentReviewTaskMapper taskMapper;
    private final AiContentReviewIssueMapper issueMapper;
    private final AiContentPreviewTaskMapper previewTaskMapper;
    private final AiContentPreviewProperties previewProperties;

    public AiContentReviewTaskService(AiContentReviewTaskMapper taskMapper,
                                      AiContentReviewIssueMapper issueMapper,
                                      AiContentPreviewTaskMapper previewTaskMapper,
                                      AiContentPreviewProperties previewProperties) {
        this.taskMapper = taskMapper;
        this.issueMapper = issueMapper;
        this.previewTaskMapper = previewTaskMapper;
        this.previewProperties = previewProperties;
    }

    /** 视频开关关闭时把 Python 给出的 VIDEO 方案归一化为图片轮播。 */
    private String resolveFormat(String format) {
        return "VIDEO".equals(format) && previewProperties.isVideoEnabled() ? "VIDEO" : "IMAGE_CAROUSEL";
    }

    public AiContentReviewTaskEntity require(Long taskId) {
        // 所有公开操作先经过统一存在性校验，避免各接口返回不同的空值错误。
        AiContentReviewTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_REVIEW_TASK_NOT_FOUND);
        return task;
    }

    /** 原子抢占 PENDING 任务，防止 HTTP 创建和定时扫描重复执行同一次模型审核。 */
    public boolean claim(Long taskId) { return taskMapper.claim(taskId) == 1; }

    /** 定时任务只扫描 PENDING；FAILED 不会自动重试，避免重复调用模型。 */
    public List<AiContentReviewTaskEntity> pending(int limit) { return taskMapper.selectPending(limit); }

    /** PROCESSING 长时间不更新通常表示服务中断，改为 FAILED 后交给用户决定是否重试。 */
    public void failStale(LocalDateTime before) { taskMapper.failStale(before); }

    @Transactional
    public AiContentReviewTaskEntity prepareRetry(Long taskId) {
        AiContentReviewTaskEntity task = require(taskId);
        // SQL 的 where status='FAILED' 才是真正的并发保护；这里不依赖查询到的旧状态做判断。
        if (taskMapper.prepareRetry(taskId) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_REVIEW_TASK_CONFLICT,
                    "只有失败的审核任务可以重试；未通过的内容必须先重写");
        }
        // 重试会生成一套全新的审核结果，旧问题不能和新问题混在一起。
        issueMapper.deleteByTaskId(taskId);
        return task;
    }

    @Transactional
    public void saveResult(Long taskId, JSONObject response) {
        String reviewStatus = response.getString("review_status");
        JSONObject result = response.getJSONObject("review_result");
        // Java 只接受约定的两个业务终态，未知值统一按 FAILED 保存，方便用户原地重试。
        String status = switch (reviewStatus == null ? "" : reviewStatus) {
            case "AWAITING_APPROVAL" -> "AWAITING_APPROVAL";
            case "REJECTED" -> "REJECTED";
            default -> "FAILED";
        };
        Integer score = result == null ? null : result.getInteger("score");
        String summary = result == null ? null : truncate(result.getString("summary"));
        String error = "FAILED".equals(status) ? truncate(response.getString("review_error")) : null;
        JSONObject previewPlan = response.getJSONObject("preview_plan");
        // 归一化仍保留：视频开关关闭时，历史 Python 或旧方案返回的 VIDEO 会在人工批准前
        // 退回图片轮播，避免意外启动耗时的视频渲染；开关打开时沿用 Python 的判定。
        String previewFormat = previewPlan == null ? null : resolveFormat(previewPlan.getString("format"));
        if (previewPlan != null) previewPlan.put("format", previewFormat);

        // 问题明细与任务终态在同一事务写入，前端不会读到“任务完成但问题尚未保存”的中间状态。
        issueMapper.deleteByTaskId(taskId);
        JSONArray issues = result == null ? null : result.getJSONArray("issues");
        if (issues != null) {
            for (Object value : issues) issueMapper.insert(toIssue(taskId, (JSONObject) value));
        }
        if (taskMapper.markFinished(taskId, status, score, summary, error,
                previewFormat, previewPlan == null ? null : previewPlan.toJSONString()) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_REVIEW_TASK_OPERATION_FAILED);
        }
    }

    public void fail(Long taskId, Throwable throwable) {
        // 保留 Python 错误编码和可读消息；过长堆栈只写日志，不写入业务表。
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        taskMapper.markFinished(taskId, "FAILED", null, null, truncate(message), null, null);
    }

    public ContentReviewTaskRes approve(Long taskId, Long userId) {
        AiContentReviewTaskEntity task = require(taskId);
        // 已经 APPROVED 时直接返回当前结果，使前端重复点击或请求重放保持幂等。
        if (!"APPROVED".equals(task.getStatus()) && taskMapper.approve(taskId, userId) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_REVIEW_TASK_CONFLICT,
                    "只有审核完成或审核未通过的任务可以人工批准");
        }
        return detail(taskId);
    }

    /** 单图被替换后旧审核结论立即失效，并重新进入自动审核队列。 */
    @Transactional
    public void resetAfterMaterialChange(Long taskId) {
        require(taskId);
        if (taskMapper.resetAfterMaterialChange(taskId) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_REVIEW_TASK_CONFLICT,
                    "审核已确认通过或正在执行，当前不能替换图片");
        }
        issueMapper.deleteByTaskId(taskId);
    }

    public ContentReviewTaskRes detail(Long taskId) {
        // 页面只读取 Java 持久化结果，不依赖 Python 的内存 checkpoint 查询详情。
        AiContentReviewTaskEntity task = require(taskId);
        ContentReviewTaskRes response = new ContentReviewTaskRes();
        response.setTaskId(task.getId());
        response.setConversationId(task.getConversationId());
        response.setContentRevision(task.getContentRevision());
        response.setMaterialTaskId(task.getMaterialTaskId());
        response.setStatus(task.getStatus());
        response.setScore(task.getScore());
        response.setSummary(task.getSummary());
        response.setPreviewFormat(task.getPreviewFormat());
        response.setPreviewPlan(task.getPreviewPlanJson() == null
                ? null : JSONObject.parse(task.getPreviewPlanJson()));
        var previewTask = previewTaskMapper.selectByReviewTaskId(taskId);
        if (previewTask != null) {
            response.setPreviewTaskId(previewTask.getId());
            response.setPreviewStatus(previewTask.getStatus());
        }
        response.setErrorMessage(task.getErrorMessage());
        response.setApprovedBy(task.getApprovedBy());
        response.setApprovedAt(task.getApprovedAt());
        response.setStartedAt(task.getStartedAt());
        response.setFinishedAt(task.getFinishedAt());
        List<ContentReviewIssueRes> issues = new ArrayList<>();
        for (AiContentReviewIssueEntity issue : issueMapper.selectByTaskId(taskId)) issues.add(toResponse(issue));
        response.setIssues(issues);
        return response;
    }

    private AiContentReviewIssueEntity toIssue(Long taskId, JSONObject value) {
        // Python 使用 snake_case，Java 实体使用 camelCase，在持久化边界集中转换。
        AiContentReviewIssueEntity issue = new AiContentReviewIssueEntity();
        issue.setTaskId(taskId);
        issue.setIssueCode(value.getString("issue_code"));
        issue.setCategory(value.getString("category"));
        issue.setSeverity(value.getString("severity"));
        issue.setLocation(value.getString("location"));
        issue.setDescription(value.getString("description"));
        issue.setSuggestion(value.getString("suggestion"));
        issue.setSource(value.getString("source"));
        return issue;
    }

    private ContentReviewIssueRes toResponse(AiContentReviewIssueEntity issue) {
        ContentReviewIssueRes response = new ContentReviewIssueRes();
        response.setIssueCode(issue.getIssueCode());
        response.setCategory(issue.getCategory());
        response.setSeverity(issue.getSeverity());
        response.setLocation(issue.getLocation());
        response.setDescription(issue.getDescription());
        response.setSuggestion(issue.getSuggestion());
        response.setSource(issue.getSource());
        return response;
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
