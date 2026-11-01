package com.yanque.aimedia.service;

import com.yanque.aimedia.common.AiMediaErrors;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.mapper.AiMediaAudioMapper;
import com.yanque.aimedia.mapper.AiMediaVideoMapper;
import com.yanque.aimedia.pojo.entity.AiMediaAudioEntity;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaAudioRes;
import org.springframework.stereotype.Service;

/** 音频产物查询服务。 */
@Service
public class AiMediaAudioService {
    private final AiMediaVideoMapper videoMapper;
    private final AiMediaAudioMapper audioMapper;
    private final AiMediaObjectStorageService objectStorageService;

    public AiMediaAudioService(AiMediaVideoMapper videoMapper,
                               AiMediaAudioMapper audioMapper,
                               AiMediaObjectStorageService objectStorageService) {
        this.videoMapper = videoMapper;
        this.audioMapper = audioMapper;
        this.objectStorageService = objectStorageService;
    }

    /** 查询视频最新的音频产物，并实时生成播放地址。 */
    public AiMediaAudioRes preview(Long videoId) {
        if (videoMapper.selectById(videoId) == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_NOT_FOUND);
        }

        AiMediaAudioEntity audio = audioMapper.selectLatestByVideoId(videoId);
        if (audio == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_AUDIO_NOT_FOUND);
        }

        AiMediaAudioRes result = new AiMediaAudioRes();
        result.setId(audio.getId());
        result.setVideoId(audio.getVideoId());
        result.setFileFormat(audio.getFileFormat());
        result.setFileSize(audio.getFileSize());
        result.setDurationMs(audio.getDurationMs());
        result.setSampleRate(audio.getSampleRate());
        result.setChannels(audio.getChannels());
        // 预签名 URL 有有效期，因此只在查询时生成，不写入 ai_media_audio。
        result.setPreviewUrl(objectStorageService.createAudioPreviewUrl(audio.getObjectKey()));
        return result;
    }
}
