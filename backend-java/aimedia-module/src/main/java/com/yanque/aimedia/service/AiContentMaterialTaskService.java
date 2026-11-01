package com.yanque.aimedia.service;

import com.yanque.aimedia.common.AiMediaErrors;
import cn.hutool.core.util.StrUtil;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.pojo.vo.resvo.PresignDownloadRes;
import com.yanque.common.utils.TosPresignService;
import com.yanque.aimedia.mapper.AiContentMaterialItemMapper;
import com.yanque.aimedia.mapper.AiContentMaterialTaskMapper;
import com.yanque.aimedia.mapper.AiContentReviewTaskMapper;
import com.yanque.aimedia.pojo.entity.AiContentMaterialItemEntity;
import com.yanque.aimedia.pojo.entity.AiContentMaterialTaskEntity;
import com.yanque.aimedia.pojo.entity.AiContentReviewTaskEntity;
import com.yanque.aimedia.pojo.vo.resvo.ContentMaterialItemRes;
import com.yanque.aimedia.pojo.vo.resvo.ContentMaterialTaskCreateRes;
import com.yanque.aimedia.pojo.vo.resvo.ContentMaterialTaskRes;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 保存文章素材后台任务以及最终视频、图片结果。 */
@Service
public class AiContentMaterialTaskService {
    private final AiContentMaterialTaskMapper taskMapper;
    private final AiContentMaterialItemMapper itemMapper;
    private final AiContentReviewTaskMapper reviewTaskMapper;
    private final TosPresignService tosPresignService;

    public AiContentMaterialTaskService(AiContentMaterialTaskMapper taskMapper,
                                        AiContentMaterialItemMapper itemMapper,
                                        AiContentReviewTaskMapper reviewTaskMapper,
                                        TosPresignService tosPresignService) {
        this.taskMapper = taskMapper;
        this.itemMapper = itemMapper;
        this.reviewTaskMapper = reviewTaskMapper;
        this.tosPresignService = tosPresignService;
    }

    /** 同一会话和文章版本只创建一个任务，重复点击直接返回原任务。 */
    @Transactional
    public ContentMaterialTaskCreateRes create(String conversationId, Integer revision, Long userId) {
        AiContentMaterialTaskEntity existing = taskMapper.selectByRevision(conversationId, revision);
        if (existing != null) {
            return new ContentMaterialTaskCreateRes(existing.getId(), existing.getStatus());
        }
        AiContentMaterialTaskEntity task = new AiContentMaterialTaskEntity();
        task.setConversationId(conversationId);
        task.setContentRevision(revision);
        task.setStatus("PENDING");
        task.setCreatedBy(userId);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException exception) {
            task = taskMapper.selectByRevision(conversationId, revision);
        }
        if (task == null || task.getId() == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_OPERATION_FAILED);
        }
        return new ContentMaterialTaskCreateRes(task.getId(), task.getStatus());
    }

    public AiContentMaterialTaskEntity require(Long taskId) {
        AiContentMaterialTaskEntity task = taskMapper.selectById(taskId);
        if (task == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_NOT_FOUND);
        }
        return task;
    }

    public boolean claim(Long taskId) {
        return taskMapper.claim(taskId) == 1;
    }

    public List<AiContentMaterialTaskEntity> pending(int limit) {
        return taskMapper.selectPending(limit);
    }

    public void failStale(LocalDateTime updatedBefore) {
        taskMapper.failStale(updatedBefore);
    }

    @Transactional
    public AiContentMaterialTaskEntity prepareRetry(Long taskId) {
        AiContentMaterialTaskEntity task = require(taskId);
        if (taskMapper.prepareRetry(taskId) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_CONFLICT,
                    "只有失败或部分失败的素材任务可以重试");
        }
        return task;
    }

    /** Python 返回后覆盖本任务结果，并确认所有生成图片已经真实上传。 */
    @Transactional
    public Long saveResult(AiContentMaterialTaskEntity task, JSONObject result) {
        JSONArray matches = result.getJSONArray("material_matches");
        itemMapper.deleteByTaskId(task.getId());
        if (matches != null) {
            for (Object value : matches) {
                AiContentMaterialItemEntity item = toItem(task.getId(), (JSONObject) value);
                verifyGeneratedImage(item);
                itemMapper.insert(item);
            }
        }
        String workflowStatus = result.getString("status");
        String status = switch (workflowStatus == null ? "" : workflowStatus) {
            case "MATERIAL_MATCHING_COMPLETED" -> "SUCCESS";
            case "MATERIAL_MATCHING_PARTIAL_FAILED" -> "PARTIAL_FAILED";
            default -> "FAILED";
        };
        String error = "SUCCESS".equals(status) ? null : result.getString("message");
        if (taskMapper.markFinished(task.getId(), status, truncate(error)) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_OPERATION_FAILED);
        }
        // 部分失败时仍需先修复图片，不能让不完整素材进入内容审核。
        if (!"SUCCESS".equals(status)) return null;

        // 素材结果与审核任务在同一事务提交，前端不会看到缺少审核任务的 SUCCESS。
        // 唯一键 conversation_id + content_revision 保证重复回调不会重复创建审核任务。
        AiContentReviewTaskEntity review = reviewTaskMapper.selectByRevision(
                task.getConversationId(), task.getContentRevision());
        if (review == null) {
            review = new AiContentReviewTaskEntity();
            review.setConversationId(task.getConversationId());
            review.setContentRevision(task.getContentRevision());
            review.setMaterialTaskId(task.getId());
            review.setStatus("PENDING");
            review.setCreatedBy(task.getCreatedBy());
            reviewTaskMapper.insert(review);
        }
        return review.getId();
    }

    public void fail(Long taskId, Throwable throwable) {
        String message = throwable.getMessage() == null
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
        taskMapper.markFinished(taskId, "FAILED", truncate(message));
    }

    public ContentMaterialTaskRes detail(Long taskId) {
        AiContentMaterialTaskEntity task = require(taskId);
        ContentMaterialTaskRes response = new ContentMaterialTaskRes();
        response.setTaskId(task.getId());
        response.setConversationId(task.getConversationId());
        response.setContentRevision(task.getContentRevision());
        response.setStatus(task.getStatus());
        response.setErrorMessage(task.getErrorMessage());
        response.setStartedAt(task.getStartedAt());
        response.setFinishedAt(task.getFinishedAt());
        AiContentReviewTaskEntity review = reviewTaskMapper.selectByMaterialTaskId(taskId);
        if (review != null) {
            response.setReviewTaskId(review.getId());
            response.setReviewStatus(review.getStatus());
        }
        List<ContentMaterialItemRes> items = new ArrayList<>();
        for (AiContentMaterialItemEntity item : itemMapper.selectByTaskId(taskId)) {
            items.add(toResponse(item));
        }
        response.setMaterialMatches(items);
        return response;
    }

    /** 在产生付费图片调用前检查目标和审核状态，APPROVED 后禁止原地替换。 */
    public void validateImageRegenerate(Long taskId, String needId) {
        AiContentMaterialTaskEntity task = require(taskId);
        AiContentMaterialItemEntity item = itemMapper.selectByTaskAndNeed(taskId, needId);
        AiContentReviewTaskEntity review = reviewTaskMapper.selectByMaterialTaskId(taskId);
        if (!"SUCCESS".equals(task.getStatus()) || item == null
                || !"GENERATED_IMAGE".equals(item.getAssetType())) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_CONFLICT,
                    "指定素材不是可重做的生成图片");
        }
        if (review != null && ("APPROVED".equals(review.getStatus())
                || "PENDING".equals(review.getStatus()) || "PROCESSING".equals(review.getStatus()))) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_REVIEW_TASK_CONFLICT,
                    "审核已通过或正在执行，当前不能替换图片");
        }
    }

    /** 只覆盖指定 need 的图片，不删除其他已经成功的视频和图片。 */
    @Transactional
    public Long replaceGeneratedImage(AiContentMaterialTaskEntity task, String needId, JSONObject result) {
        if (!"SUCCESS".equals(task.getStatus())) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_CONFLICT,
                    "只有已经完成的素材任务可以单独重做图片");
        }
        AiContentMaterialItemEntity existing = itemMapper.selectByTaskAndNeed(task.getId(), needId);
        if (existing == null || !"GENERATED_IMAGE".equals(existing.getAssetType())) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_CONFLICT,
                    "指定素材不是可重做的生成图片");
        }
        if (!"MATERIAL_MATCHING_COMPLETED".equals(result.getString("status"))) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_OPERATION_FAILED,
                    result.getString("message"));
        }
        JSONObject changed = null;
        JSONArray matches = result.getJSONArray("material_matches");
        if (matches != null) for (Object value : matches) {
            JSONObject candidate = (JSONObject) value;
            if (needId.equals(candidate.getString("need_id"))) changed = candidate;
        }
        if (changed == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_OPERATION_FAILED,
                    "图片重做结果缺少目标素材");
        }
        AiContentMaterialItemEntity replacement = toItem(task.getId(), changed);
        replacement.setId(existing.getId());
        verifyGeneratedImage(replacement);
        if (itemMapper.updateGeneratedImage(replacement) != 1) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_OPERATION_FAILED);
        }
        AiContentReviewTaskEntity review = reviewTaskMapper.selectByMaterialTaskId(task.getId());
        return review == null ? null : review.getId();
    }

    private void verifyGeneratedImage(AiContentMaterialItemEntity item) {
        if (!"GENERATED_IMAGE".equals(item.getAssetType())) {
            return;
        }
        if (StrUtil.isBlank(item.getImageObjectKey()) || item.getImageFileSize() == null
                || !tosPresignService.existsWithSize(item.getImageObjectKey(), item.getImageFileSize())) {
            throw AiMediaErrors.of(AiMediaErrorCode.CONTENT_MATERIAL_TASK_OPERATION_FAILED,
                    "生成图片尚未完整上传到 TOS");
        }
    }

    private AiContentMaterialItemEntity toItem(Long taskId, JSONObject source) {
        AiContentMaterialItemEntity item = new AiContentMaterialItemEntity();
        item.setTaskId(taskId);
        item.setNeedId(source.getString("need_id"));
        item.setArticleExcerpt(source.getString("article_excerpt"));
        item.setAssetType(source.getString("asset_type"));
        item.setMaterialId(source.getLong("material_id"));
        item.setVideoId(source.getLong("video_id"));
        item.setClipType(source.getString("clip_type"));
        item.setDurationMs(source.getLong("duration_ms"));
        item.setRerankScore(source.getBigDecimal("rerank_score"));
        item.setRenderEngine(source.getString("render_engine"));
        item.setVisualType(source.getString("visual_type"));
        JSONObject visualSpec = source.getJSONObject("visual_spec");
        item.setVisualSpecJson(visualSpec == null ? null : visualSpec.toJSONString());
        item.setImagePrompt(source.getString("image_prompt"));
        item.setMatchReason(source.getString("match_reason"));
        item.setProviderTaskId(source.getString("provider_task_id"));
        item.setImageObjectKey(source.getString("image_object_key"));
        item.setImageFileSize(source.getLong("image_file_size"));
        item.setImageWidth(source.getInteger("image_width"));
        item.setImageHeight(source.getInteger("image_height"));
        item.setImageError(source.getString("image_error"));
        item.setVisualReviewScore(source.getInteger("visual_review_score"));
        item.setVisualReviewSummary(source.getString("visual_review_summary"));
        item.setGenerationAttempt(source.getInteger("generation_attempt"));
        item.setRegenerateFeedback(source.getString("regenerate_feedback"));
        return item;
    }

    private ContentMaterialItemRes toResponse(AiContentMaterialItemEntity item) {
        ContentMaterialItemRes response = new ContentMaterialItemRes();
        response.setNeedId(item.getNeedId());
        response.setArticleExcerpt(item.getArticleExcerpt());
        response.setAssetType(item.getAssetType());
        response.setMaterialId(item.getMaterialId());
        response.setVideoId(item.getVideoId());
        response.setClipType(item.getClipType());
        response.setDurationMs(item.getDurationMs());
        response.setRerankScore(item.getRerankScore());
        response.setRenderEngine(item.getRenderEngine());
        response.setVisualType(item.getVisualType());
        response.setVisualSpec(item.getVisualSpecJson() == null ? null : JSONObject.parse(item.getVisualSpecJson()));
        response.setImagePrompt(item.getImagePrompt());
        response.setMatchReason(item.getMatchReason());
        response.setProviderTaskId(item.getProviderTaskId());
        response.setImageObjectKey(item.getImageObjectKey());
        response.setImageFileSize(item.getImageFileSize());
        response.setImageWidth(item.getImageWidth());
        response.setImageHeight(item.getImageHeight());
        response.setImageError(item.getImageError());
        response.setVisualReviewScore(item.getVisualReviewScore());
        response.setVisualReviewSummary(item.getVisualReviewSummary());
        response.setGenerationAttempt(item.getGenerationAttempt());
        response.setRegenerateFeedback(item.getRegenerateFeedback());
        if (StrUtil.isNotBlank(item.getImageObjectKey())) {
            PresignDownloadRes presign = tosPresignService.presignDownload(item.getImageObjectKey());
            response.setImageUrl(presign.getDownloadUrl());
        }
        return response;
    }

    private String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
