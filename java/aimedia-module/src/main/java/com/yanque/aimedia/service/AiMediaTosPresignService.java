package com.yanque.aimedia.service;

import cn.hutool.core.util.StrUtil;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.comm.HttpMethod;
import com.volcengine.tos.model.object.HeadObjectV2Input;
import com.volcengine.tos.model.object.HeadObjectV2Output;
import com.volcengine.tos.model.object.PreSignedURLInput;
import com.volcengine.tos.model.object.PreSignedURLOutput;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.aimedia.common.AiMediaErrors;
import com.yanque.aimedia.pojo.vo.resvo.PresignDownloadRes;
import com.yanque.aimedia.pojo.vo.resvo.PresignUploadRes;
import com.yanque.common.config.TosProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * AI 媒体模块的 TOS 预签名服务。
 * <p>
 * 与目标项目原有的 {@code com.yanque.common.utils.TosPresignService} 不同，
 * 这里按已确定的 objectKey 签名（而不是按目录+文件名新建对象），并提供
 * HeadObject 大小校验，满足视频直传登记和上传产物核对的需要。
 */
@Service
@RequiredArgsConstructor
public class AiMediaTosPresignService {

    private final TOSV2 tosClient;
    private final TosProperties properties;

    public PresignUploadRes presignUpload(String objectKey) {
        String normalizedKey = validateObjectKey(objectKey);
        long expires = defaultExpireSeconds();
        try {
            PreSignedURLOutput output = tosClient.preSignedURL(PreSignedURLInput.builder()
                    .bucket(properties.getBucketName())
                    .key(normalizedKey)
                    .httpMethod(HttpMethod.PUT)
                    .expires(expires)
                    .build());
            Map<String, String> headers = output.getSignedHeader() == null
                    ? Map.of() : output.getSignedHeader();
            return new PresignUploadRes(output.getSignedUrl(), normalizedKey, expires, headers);
        } catch (Exception exception) {
            throw AiMediaErrors.of(AiMediaErrorCode.TOS_PRESIGN_FAILED);
        }
    }

    public PresignDownloadRes presignDownload(String objectKey) {
        return presignDownload(objectKey, defaultExpireSeconds());
    }

    /** 为需要较长处理时间的服务生成指定有效期的下载地址。 */
    public PresignDownloadRes presignDownload(String objectKey, long expireSeconds) {
        String normalizedKey = validateObjectKey(objectKey);
        try {
            PreSignedURLOutput output = tosClient.preSignedURL(PreSignedURLInput.builder()
                    .bucket(properties.getBucketName())
                    .key(normalizedKey)
                    .httpMethod(HttpMethod.GET)
                    .expires(expireSeconds)
                    .build());
            return new PresignDownloadRes(output.getSignedUrl(), normalizedKey, expireSeconds);
        } catch (Exception exception) {
            throw AiMediaErrors.of(AiMediaErrorCode.TOS_PRESIGN_FAILED);
        }
    }

    /** 校验对象已经上传完成且大小与前端登记值一致。 */
    public boolean existsWithSize(String objectKey, long expectedSize) {
        String normalizedKey = validateObjectKey(objectKey);
        try {
            HeadObjectV2Output output = tosClient.headObject(HeadObjectV2Input.builder()
                    .bucket(properties.getBucketName())
                    .key(normalizedKey)
                    .build());
            return output.getContentLength() == expectedSize;
        } catch (Exception exception) {
            return false;
        }
    }

    private long defaultExpireSeconds() {
        return properties.getPresignExpires() == null ? 3600L : properties.getPresignExpires();
    }

    private String validateObjectKey(String objectKey) {
        String normalizedKey = StrUtil.trim(objectKey);
        if (StrUtil.isBlank(normalizedKey) || normalizedKey.startsWith("/")
                || normalizedKey.contains("..") || normalizedKey.contains("\\")) {
            throw AiMediaErrors.of(AiMediaErrorCode.TOS_OBJECT_KEY_INVALID);
        }
        return normalizedKey;
    }
}
