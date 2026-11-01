package com.yanque.aimedia.service;

import com.yanque.aimedia.common.AiMediaErrors;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.common.config.TosProperties;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.pojo.vo.resvo.PresignDownloadRes;
import com.yanque.common.utils.TosPresignService;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.model.object.ObjectMetaRequestOptions;
import com.volcengine.tos.model.object.PutObjectFromFileInput;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/** AI 媒体处理使用的对象存储能力。 */
@Service
public class AiMediaObjectStorageService {
    private final TOSV2 tosClient;
    private final TosProperties tosProperties;
    private final TosPresignService tosPresignService;

    public AiMediaObjectStorageService(TOSV2 tosClient,
                                       TosProperties tosProperties,
                                       TosPresignService tosPresignService) {
        this.tosClient = tosClient;
        this.tosProperties = tosProperties;
        this.tosPresignService = tosPresignService;
    }

    /** 每次执行时重新签名，避免把短时 URL 持久化到数据库。 */
    public String createVideoInputUrl(String videoObjectKey) {
        // 返回的是短时有效的 GET 地址，只在当前处理步骤内使用。
        PresignDownloadRes result = tosPresignService.presignDownload(videoObjectKey);
        return result.getDownloadUrl();
    }

    /** 为前端播放音频生成短时有效的下载地址。 */
    public String createAudioPreviewUrl(String audioObjectKey) {
        return tosPresignService.presignDownload(audioObjectKey).getDownloadUrl();
    }

    /** 为前端播放独立视频素材生成短时有效地址。 */
    public PresignDownloadRes createVideoClipPreviewUrl(String clipObjectKey) {
        return tosPresignService.presignDownload(clipObjectKey);
    }

    /** 为火山闲时 ASR 生成长时有效地址，避免排队期间短签名失效。 */
    public String createAsrAudioUrl(String audioObjectKey, long expireSeconds) {
        return tosPresignService.presignDownload(audioObjectKey, expireSeconds).getDownloadUrl();
    }

    /** PREPARE_VIDEO 在真正转码前确认原视频仍然存在且大小没有变化。 */
    public void verifyVideo(String videoObjectKey, long expectedSize) {
        if (!tosPresignService.existsWithSize(videoObjectKey, expectedSize)) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_UPLOAD_NOT_COMPLETED,
                    "TOS 中的原始视频不存在或文件大小不一致");
        }
    }

    public void uploadAudio(Path localFile, String objectKey, long expectedSize) {
        try {
            // 后端持有 TOS SDK 凭证，音频中间产物无需再走浏览器预签名上传。
            tosClient.putObjectFromFile(PutObjectFromFileInput.builder()
                    .bucket(tosProperties.getBucket())
                    .key(objectKey)
                    .file(localFile.toFile())
                    .options(ObjectMetaRequestOptions.builder().contentType("audio/mpeg").build())
                    .build());

            // SDK 返回成功后再次校验对象大小，确认完整文件已经落到 TOS。
            if (!tosPresignService.existsWithSize(objectKey, expectedSize)) {
                throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_AUDIO_UPLOAD_FAILED,
                        "音频上传后校验失败");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_AUDIO_UPLOAD_FAILED,
                    "音频上传 TOS 失败：" + safeMessage(exception));
        }
    }

    /** 上传 FFmpeg 生成的视频素材，并校验对象大小。 */
    public void uploadVideoClip(Path localFile, String objectKey, long expectedSize) {
        try {
            tosClient.putObjectFromFile(PutObjectFromFileInput.builder()
                    .bucket(tosProperties.getBucket())
                    .key(objectKey)
                    .file(localFile.toFile())
                    .options(ObjectMetaRequestOptions.builder().contentType("video/mp4").build())
                    .build());
            if (!tosPresignService.existsWithSize(objectKey, expectedSize)) {
                throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_MATERIAL_CLIP_UPLOAD_FAILED,
                        "视频素材上传后校验失败");
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_MATERIAL_CLIP_UPLOAD_FAILED,
                    "视频素材上传 TOS 失败：" + safeMessage(exception));
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
}
