package com.yanque.aimedia.service;

import com.yanque.aimedia.common.AiMediaErrors;
import com.alibaba.fastjson2.JSON;
import cn.hutool.core.util.StrUtil;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.common.pojo.PageResult;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.pojo.vo.resvo.PresignDownloadRes;
import com.yanque.aimedia.mapper.AiMediaVideoClipMapper;
import com.yanque.aimedia.mapper.AiMediaVideoMapper;
import com.yanque.aimedia.pojo.entity.AiMediaVideoClipEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoClipListRow;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaMaterialPageReq;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaVideoClipRes;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 视频素材片段查询服务，不参与步骤执行和状态流转。 */
@Service
public class AiMediaVideoClipQueryService {
    private static final Set<String> CLIP_TYPES = Set.of(
            "Q_AND_A", "VIEWPOINT", "KNOWLEDGE", "STORY", "CASE", "TUTORIAL");
    private final AiMediaVideoMapper videoMapper;
    private final AiMediaVideoClipMapper clipMapper;
    private final AiMediaObjectStorageService objectStorageService;

    public AiMediaVideoClipQueryService(AiMediaVideoMapper videoMapper,
                                        AiMediaVideoClipMapper clipMapper,
                                        AiMediaObjectStorageService objectStorageService) {
        this.videoMapper = videoMapper;
        this.clipMapper = clipMapper;
        this.objectStorageService = objectStorageService;
    }

    /** 返回当前视频最终入选的 0～3 条素材方案。 */
    public List<AiMediaVideoClipRes> list(Long videoId) {
        if (videoMapper.selectById(videoId) == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_NOT_FOUND);
        }
        return clipMapper.selectByVideoId(videoId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** 查询所有原视频已经产出的素材方案，供独立素材库页面使用。 */
    public PageResult<AiMediaVideoClipRes> page(AiMediaMaterialPageReq req) {
        String clipType = normalizeClipType(req.getClipType());
        PageHelper.startPage(req.getPageNum(), req.getPageSize());
        List<AiMediaVideoClipListRow> rows = clipMapper.selectPage(
                StrUtil.trim(req.getKeyword()), clipType, StrUtil.trim(req.getSpeaker()));
        PageInfo<AiMediaVideoClipListRow> pageInfo = new PageInfo<>(rows);
        return new PageResult<>(pageInfo.getTotal(), pageInfo.getPageNum(), pageInfo.getPageSize(),
                rows.stream().map(this::toResponse).toList());
    }

    /** 根据稳定的 object_key 现用现签，不将会过期的 URL 保存到素材表。 */
    public PresignDownloadRes previewUrl(Long materialId) {
        AiMediaVideoClipEntity clip = clipMapper.selectById(materialId);
        if (clip == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_MATERIAL_NOT_FOUND);
        }
        if (StrUtil.isBlank(clip.getObjectKey())) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_MATERIAL_NOT_RENDERED);
        }
        return objectStorageService.createVideoClipPreviewUrl(clip.getObjectKey());
    }

    private AiMediaVideoClipRes toResponse(AiMediaVideoClipEntity entity) {
        AiMediaVideoClipRes result = new AiMediaVideoClipRes();
        result.setId(entity.getId());
        result.setVideoId(entity.getVideoId());
        result.setTaskId(entity.getTaskId());
        result.setSegmentNo(entity.getSegmentNo());
        result.setStartMs(entity.getStartMs());
        result.setEndMs(entity.getEndMs());
        result.setDurationMs(entity.getDurationMs());
        result.setTitle(entity.getTitle());
        result.setSummary(entity.getSummary());
        result.setCoreViewpoint(entity.getCoreViewpoint());
        result.setClipType(entity.getClipType());
        result.setValueReason(entity.getValueReason());
        result.setTags(parseArray(entity.getTagsJson()));
        result.setSpeakers(parseArray(entity.getSpeakersJson()));
        result.setTranscriptText(entity.getTranscriptText());
        result.setObjectKey(entity.getObjectKey());
        result.setFileSize(entity.getFileSize());
        result.setCreatedAt(entity.getCreatedAt());
        if (entity instanceof AiMediaVideoClipListRow row) {
            result.setSourceVideoTitle(row.getSourceVideoTitle());
            result.setSourceVideoFileName(row.getSourceVideoFileName());
        }
        return result;
    }

    private String normalizeClipType(String clipType) {
        String value = StrUtil.trim(clipType);
        if (StrUtil.isBlank(value)) {
            return null;
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!CLIP_TYPES.contains(value)) {
            throw AiMediaErrors.of(AiMediaErrorCode.PARAM_VALID_FAILED, "素材类型不合法");
        }
        return value;
    }

    private List<String> parseArray(String json) {
        return json == null || json.isBlank()
                ? List.of()
                : JSON.parseArray(json, String.class);
    }
}
