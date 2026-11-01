package com.yanque.aimedia.service;

import com.yanque.aimedia.common.AiMediaErrors;
import com.yanque.aimedia.config.AiContentPreviewProperties;
import cn.hutool.core.util.StrUtil;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.pojo.vo.resvo.PresignDownloadRes;
import com.yanque.common.utils.TosPresignService;
import com.yanque.aimedia.mapper.*;
import com.yanque.aimedia.pojo.entity.*;
import com.yanque.aimedia.pojo.vo.resvo.ContentPreviewItemRes;
import com.yanque.aimedia.pojo.vo.resvo.ContentPreviewTaskRes;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 负责审核确认、最终成品任务、素材签名和成品结果持久化。 */
@Service
public class AiContentPreviewTaskService {
    private final AiContentPreviewTaskMapper taskMapper;
    private final AiContentPreviewItemMapper itemMapper;
    private final AiContentReviewTaskMapper reviewTaskMapper;
    private final AiContentMaterialItemMapper materialItemMapper;
    private final AiMediaVideoClipMapper videoClipMapper;
    private final TosPresignService tos;
    private final AiContentPreviewProperties previewProperties;

    public AiContentPreviewTaskService(AiContentPreviewTaskMapper taskMapper,
                                       AiContentPreviewItemMapper itemMapper,
                                       AiContentReviewTaskMapper reviewTaskMapper,
                                       AiContentMaterialItemMapper materialItemMapper,
                                       AiMediaVideoClipMapper videoClipMapper,
                                       TosPresignService tos,
                                       AiContentPreviewProperties previewProperties) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.reviewTaskMapper = reviewTaskMapper;
        this.materialItemMapper = materialItemMapper;
        this.videoClipMapper = videoClipMapper;
        this.tos = tos;
        this.previewProperties = previewProperties;
    }

    /** 视频开关关闭时把 VIDEO 一律降级为图片轮播，保持历史行为。 */
    private String resolveFormat(String format) {
        return "VIDEO".equals(format) && previewProperties.isVideoEnabled() ? "VIDEO" : "IMAGE_CAROUSEL";
    }

    /** 人工确认和唯一成品任务创建必须在同一事务完成，允许人工覆盖带 ERROR 的审核结论。 */
    @Transactional
    public AiContentPreviewTaskEntity approveAndCreate(Long reviewTaskId, Long userId) {
        AiContentReviewTaskEntity review = reviewTaskMapper.selectById(reviewTaskId);
        if (review == null) throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_REVIEW_TASK_NOT_FOUND);
        if (!"APPROVED".equals(review.getStatus())
                && reviewTaskMapper.approve(reviewTaskId, userId) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_REVIEW_TASK_CONFLICT,
                    "只有审核完成或审核未通过的任务可以人工批准");
        }
        AiContentPreviewTaskEntity existing = taskMapper.selectByReviewTaskId(reviewTaskId);
        if (existing != null) return existing;
        if (StrUtil.isBlank(review.getPreviewFormat()) || StrUtil.isBlank(review.getPreviewPlanJson())) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_OPERATION_FAILED,
                    "审核结果缺少抖音发布方案");
        }
        // 开关关闭时，VIDEO 方案在批准这一刻降级为图片轮播：视频素材只抽取一帧做卡片背景，
        // 不再转码拼接 MP4；开关打开时按审核方案原样进入视频渲染。
        JSONObject previewPlan = JSONObject.parseObject(review.getPreviewPlanJson());
        String format = resolveFormat(previewPlan.getString("format"));
        previewPlan.put("format", format);
        AiContentPreviewTaskEntity task = new AiContentPreviewTaskEntity();
        task.setReviewTaskId(reviewTaskId);
        task.setConversationId(review.getConversationId());
        task.setContentRevision(review.getContentRevision());
        task.setFormat(format);
        task.setStatus("PENDING");
        task.setPreviewPlanJson(previewPlan.toJSONString());
        task.setCreatedBy(userId);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException ignored) {
            task = taskMapper.selectByReviewTaskId(reviewTaskId);
        }
        if (task == null || task.getId() == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_OPERATION_FAILED);
        }
        return task;
    }

    public AiContentPreviewTaskEntity require(Long taskId) {
        AiContentPreviewTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_NOT_FOUND);
        return task;
    }

    public void requireProcessing(Long taskId) {
        if (!"PROCESSING".equals(require(taskId).getStatus())) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_CONFLICT,
                    "成品任务不在处理中，不能上传文件");
        }
    }

    public boolean claim(Long taskId) { return taskMapper.claim(taskId) == 1; }
    public List<AiContentPreviewTaskEntity> pending(int limit) { return taskMapper.selectPending(limit); }
    public void failStale(LocalDateTime before) { taskMapper.failStale(before); }

    @Transactional
    public AiContentPreviewTaskEntity prepareRetry(Long taskId) {
        AiContentPreviewTaskEntity task = require(taskId);
        if (taskMapper.prepareRetry(taskId) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_CONFLICT,
                    "只有失败或已完成的成品任务可以重新生成");
        }
        itemMapper.deleteByTaskId(taskId);
        return task;
    }

    /** 使用持久化方案和素材构造 Python 渲染请求，不依赖 LangGraph 内存状态。 */
    public JSONObject buildRenderPayload(AiContentPreviewTaskEntity task) {
        AiContentReviewTaskEntity review = reviewTaskMapper.selectById(task.getReviewTaskId());
        if (review == null) throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_REVIEW_TASK_NOT_FOUND);
        JSONObject plan = JSONObject.parseObject(task.getPreviewPlanJson());
        Map<String, AiContentMaterialItemEntity> materials = new HashMap<>();
        for (AiContentMaterialItemEntity item : materialItemMapper.selectByTaskId(review.getMaterialTaskId())) {
            materials.put(item.getNeedId(), item);
        }
        JSONArray renderItems = new JSONArray();
        for (Object value : plan.getJSONArray("items")) {
            JSONObject planItem = (JSONObject) value;
            String needId = planItem.getString("need_id");
            AiContentMaterialItemEntity material = materials.get(needId);
            if (material == null) {
                throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_OPERATION_FAILED,
                        "发布方案引用了不存在的素材：" + needId);
            }
            JSONObject renderItem = new JSONObject();
            renderItem.put("need_id", needId);
            renderItem.put("asset_type", material.getAssetType());
            renderItem.put("source_url", sourceUrl(material));
            renderItem.put("headline", planItem.getString("headline"));
            renderItem.put("body", planItem.getString("body"));
            renderItem.put("duration_ms", planItem.getLong("duration_ms"));
            renderItems.add(renderItem);
        }
        JSONObject payload = new JSONObject();
        payload.put("preview_task_id", task.getId());
        // 重试沿用任务自身的格式；开关关闭后历史 VIDEO 任务自动退回图片轮播。
        payload.put("format", resolveFormat(task.getFormat()));
        payload.put("cover_title", plan.getString("cover_title"));
        payload.put("publish_caption", plan.getString("publish_caption"));
        payload.put("tags", plan.getJSONArray("tags"));
        payload.put("items", renderItems);
        return payload;
    }

    private String sourceUrl(AiContentMaterialItemEntity material) {
        String key;
        if ("VIDEO_CLIP".equals(material.getAssetType())) {
            AiMediaVideoClipEntity clip = videoClipMapper.selectById(material.getMaterialId());
            key = clip == null ? null : clip.getObjectKey();
        } else {
            key = material.getImageObjectKey();
        }
        if (StrUtil.isBlank(key)) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_OPERATION_FAILED,
                    "素材文件尚未上传：" + material.getNeedId());
        }
        // 视频渲染最长 20 分钟，给内部下载签名预留足够时间。
        return tos.presignDownload(key, 1800).getDownloadUrl();
    }

    @Transactional
    public void saveResult(Long taskId, JSONObject result) {
        AiContentPreviewTaskEntity task = require(taskId);
        // Python 必须原样回显请求格式，否则说明渲染端与本次请求不是同一个产物，直接判失败。
        String expectedFormat = resolveFormat(task.getFormat());
        if (!expectedFormat.equals(result.getString("format"))) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_OPERATION_FAILED,
                    "成品渲染返回了与请求不一致的格式：" + result.getString("format"));
        }
        task.setFormat(expectedFormat);
        task.setCoverObjectKey(result.getString("cover_object_key"));
        task.setCoverFileSize(result.getLong("cover_file_size"));
        task.setVideoObjectKey(result.getString("video_object_key"));
        task.setVideoFileSize(result.getLong("video_file_size"));
        task.setDurationMs(result.getLong("duration_ms"));
        verify(task.getCoverObjectKey(), task.getCoverFileSize(), "封面");
        // 视频成片只有单个 MP4，逐张卡片校验不适用于它，改为校验整片是否完整落桶。
        if ("VIDEO".equals(expectedFormat)) {
            verify(task.getVideoObjectKey(), task.getVideoFileSize(), "成片视频");
        }
        itemMapper.deleteByTaskId(taskId);
        JSONArray items = result.getJSONArray("items");
        if (items != null) for (Object value : items) {
            JSONObject source = (JSONObject) value;
            AiContentPreviewItemEntity item = new AiContentPreviewItemEntity();
            item.setTaskId(taskId);
            item.setSequenceNo(source.getInteger("sequence_no"));
            item.setNeedId(source.getString("need_id"));
            item.setObjectKey(source.getString("object_key"));
            item.setFileSize(source.getLong("file_size"));
            item.setWidth(source.getInteger("width"));
            item.setHeight(source.getInteger("height"));
            verify(item.getObjectKey(), item.getFileSize(), "轮播图片");
            itemMapper.insert(item);
        }
        if (taskMapper.markSuccess(task) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_OPERATION_FAILED);
        }
    }

    private void verify(String key, Long size, String label) {
        if (StrUtil.isBlank(key) || size == null || !tos.existsWithSize(key, size)) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_PREVIEW_TASK_OPERATION_FAILED,
                    label + "尚未完整上传到 TOS");
        }
    }

    public void fail(Long taskId, Throwable throwable) {
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        taskMapper.markFailed(taskId, truncate(message));
    }

    public ContentPreviewTaskRes detail(Long taskId) {
        AiContentPreviewTaskEntity task = require(taskId);
        ContentPreviewTaskRes response = new ContentPreviewTaskRes();
        response.setTaskId(task.getId());
        response.setReviewTaskId(task.getReviewTaskId());
        response.setConversationId(task.getConversationId());
        response.setContentRevision(task.getContentRevision());
        response.setFormat(task.getFormat());
        response.setStatus(task.getStatus());
        response.setPreviewPlan(JSONObject.parse(task.getPreviewPlanJson()));
        response.setCoverObjectKey(task.getCoverObjectKey());
        response.setVideoObjectKey(task.getVideoObjectKey());
        response.setDurationMs(task.getDurationMs());
        response.setErrorMessage(task.getErrorMessage());
        response.setStartedAt(task.getStartedAt());
        response.setFinishedAt(task.getFinishedAt());
        if (StrUtil.isNotBlank(task.getCoverObjectKey())) response.setCoverUrl(download(task.getCoverObjectKey()));
        if (StrUtil.isNotBlank(task.getVideoObjectKey())) response.setVideoUrl(download(task.getVideoObjectKey()));
        List<ContentPreviewItemRes> items = new ArrayList<>();
        for (AiContentPreviewItemEntity item : itemMapper.selectByTaskId(taskId)) {
            ContentPreviewItemRes value = new ContentPreviewItemRes();
            value.setSequenceNo(item.getSequenceNo()); value.setNeedId(item.getNeedId());
            value.setObjectKey(item.getObjectKey()); value.setImageUrl(download(item.getObjectKey()));
            value.setFileSize(item.getFileSize()); value.setWidth(item.getWidth()); value.setHeight(item.getHeight());
            items.add(value);
        }
        response.setItems(items);
        return response;
    }

    private String download(String key) {
        PresignDownloadRes value = tos.presignDownload(key);
        return value.getDownloadUrl();
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
