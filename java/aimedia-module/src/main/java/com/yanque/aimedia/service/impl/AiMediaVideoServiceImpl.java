package com.yanque.aimedia.service.impl;

import com.yanque.aimedia.common.AiMediaErrors;
import cn.hutool.core.util.StrUtil;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.common.pojo.PageResult;
import com.yanque.aimedia.common.AiMediaUserContext;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.pojo.vo.resvo.PresignDownloadRes;
import com.yanque.aimedia.pojo.vo.resvo.PresignUploadRes;
import com.yanque.common.utils.TosPresignService;
import com.yanque.aimedia.mapper.AiMediaVideoMapper;
import com.yanque.aimedia.pojo.entity.AiMediaVideoEntity;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaVideoCreateReq;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaVideoPageReq;
import com.yanque.aimedia.pojo.vo.reqvo.AiMediaVideoPresignReq;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaVideoCreateRes;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaVideoRes;
import com.yanque.aimedia.service.AiMediaVideoService;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AiMediaVideoServiceImpl implements AiMediaVideoService {
    private static final long FIVE_GIB = 5L * 1024 * 1024 * 1024;
    private static final String OBJECT_KEY_ROOT = "ai/media/videos/";
    private static final Set<String> FILE_TYPES = Set.of("mp4", "mov", "webm", "m4v");
    private static final Set<String> PROCESS_STATUSES =
            Set.of("NOT_STARTED", "PENDING", "PROCESSING", "SUCCESS", "FAILED");
    private static final Pattern OBJECT_KEY_PATTERN = Pattern.compile(
            "^ai/media/videos/\\d{4}/\\d{2}/[0-9a-fA-F-]{36}\\.(mp4|mov|webm|m4v)$");

    private final AiMediaVideoMapper videoMapper;
    private final TosPresignService tosPresignService;

    public AiMediaVideoServiceImpl(AiMediaVideoMapper videoMapper, TosPresignService tosPresignService) {
        this.videoMapper = videoMapper;
        this.tosPresignService = tosPresignService;
    }

    @Override
    public PageResult<AiMediaVideoRes> page(AiMediaVideoPageReq req) {
        String processStatus = normalizeProcessStatus(req.getProcessStatus());
        PageHelper.startPage(req.getPageNum(), req.getPageSize());
        List<AiMediaVideoEntity> rows = videoMapper.selectPage(
                StrUtil.trim(req.getKeyword()), processStatus);
        PageInfo<AiMediaVideoEntity> info = new PageInfo<>(rows);
        return new PageResult<>(info.getTotal(), info.getPageNum(), info.getPageSize(),
                rows.stream().map(this::toRes).toList());
    }

    @Override
    public PresignUploadRes presignUpload(AiMediaVideoPresignReq req) {
        String fileName = StrUtil.trim(req.getFileName());
        String fileType = validateFile(fileName, req.getFileSize(), req.getContentType());
        LocalDate today = LocalDate.now();
        String datePath = today.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String objectKey = OBJECT_KEY_ROOT + datePath + "/" + UUID.randomUUID() + "." + fileType;
        return tosPresignService.presignUpload(objectKey);
    }

    @Override
    public AiMediaVideoCreateRes create(AiMediaVideoCreateReq req) {
        String originalFileName = StrUtil.trim(req.getOriginalFileName());
        String fileType = validateFile(originalFileName, req.getFileSize(), req.getContentType());
        String objectKey = StrUtil.trim(req.getObjectKey());
        validateObjectKey(objectKey, fileType);
        if (!tosPresignService.existsWithSize(objectKey, req.getFileSize())) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_UPLOAD_NOT_COMPLETED);
        }
        if (videoMapper.selectByObjectKey(objectKey) != null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_OPERATION_FAILED, "该视频已经登记，请勿重复提交");
        }

        AiMediaVideoEntity video = new AiMediaVideoEntity();
        video.setTitle(StrUtil.trim(req.getTitle()));
        video.setDescription(StrUtil.trim(req.getDescription()));
        video.setOriginalFileName(originalFileName);
        video.setObjectKey(objectKey);
        video.setFileType(fileType);
        video.setContentType(StrUtil.trim(req.getContentType()));
        video.setFileSize(req.getFileSize());
        video.setDurationMs(req.getDurationMs());
        video.setWidth(req.getWidth());
        video.setHeight(req.getHeight());
        video.setStatus("UPLOADED");
        video.setCreatedBy(AiMediaUserContext.getUserId());
        try {
            if (videoMapper.insert(video) != 1) {
                throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_OPERATION_FAILED);
            }
        } catch (DuplicateKeyException exception) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_OPERATION_FAILED, "该视频已经登记，请勿重复提交");
        }
        return new AiMediaVideoCreateRes(video.getId(), video.getStatus());
    }

    @Override
    public PresignDownloadRes previewUrl(Long id) {
        AiMediaVideoEntity video = videoMapper.selectById(id);
        if (video == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_NOT_FOUND);
        }
        return tosPresignService.presignDownload(video.getObjectKey());
    }

    private String validateFile(String fileName, Long fileSize, String contentType) {
        if (StrUtil.isBlank(fileName) || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_FILE_INVALID);
        }
        if (fileSize == null || fileSize <= 0) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_FILE_INVALID, "视频文件大小必须大于0");
        }
        if (fileSize >= FIVE_GIB) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_FILE_TOO_LARGE);
        }
        String fileType = resolveFileType(fileName);
        if (!FILE_TYPES.contains(fileType)) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_FILE_INVALID);
        }
        if (StrUtil.isNotBlank(contentType) && !contentType.toLowerCase(Locale.ROOT).startsWith("video/")) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_FILE_INVALID, "文件 Content-Type 必须是 video/*");
        }
        return fileType;
    }

    private void validateObjectKey(String objectKey, String fileType) {
        if (StrUtil.isBlank(objectKey) || !OBJECT_KEY_PATTERN.matcher(objectKey).matches()
                || !fileType.equals(resolveFileType(objectKey))) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_OBJECT_KEY_INVALID);
        }
    }

    private String resolveFileType(String fileName) {
        int dotIndex = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String normalizeProcessStatus(String processStatus) {
        String value = StrUtil.trim(processStatus);
        if (StrUtil.isBlank(value)) {
            return null;
        }
        value = value.toUpperCase(Locale.ROOT);
        if (!PROCESS_STATUSES.contains(value)) {
            throw AiMediaErrors.of(AiMediaErrorCode.PARAM_VALID_FAILED, "处理状态不合法");
        }
        return value;
    }

    private AiMediaVideoRes toRes(AiMediaVideoEntity video) {
        AiMediaVideoRes result = new AiMediaVideoRes();
        result.setId(video.getId());
        result.setTitle(video.getTitle());
        result.setDescription(video.getDescription());
        result.setOriginalFileName(video.getOriginalFileName());
        result.setObjectKey(video.getObjectKey());
        result.setFileType(video.getFileType());
        result.setContentType(video.getContentType());
        result.setFileSize(video.getFileSize());
        result.setDurationMs(video.getDurationMs());
        result.setWidth(video.getWidth());
        result.setHeight(video.getHeight());
        result.setUploadStatus(video.getStatus());
        result.setProcessStatus(video.getProcessStatus());
        result.setFailureStep(video.getFailureStep());
        result.setFailureMessage(video.getFailureMessage());
        result.setCreatedAt(video.getCreatedAt());
        result.setUpdatedAt(video.getUpdatedAt());
        return result;
    }
}
