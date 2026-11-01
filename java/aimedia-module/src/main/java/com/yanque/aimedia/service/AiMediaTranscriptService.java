package com.yanque.aimedia.service;

import com.yanque.aimedia.common.AiMediaErrors;
import com.yanque.aimedia.common.AiMediaErrorCode;
import com.yanque.common.pojo.PageResult;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.mapper.AiMediaTranscriptMapper;
import com.yanque.aimedia.mapper.AiMediaVideoMapper;
import com.yanque.aimedia.pojo.entity.AiMediaTranscriptEntity;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaTranscriptRes;
import com.yanque.aimedia.pojo.vo.resvo.AiMediaTranscriptUtteranceRes;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/** ASR 转写结果查询服务。 */
@Service
public class AiMediaTranscriptService {
    private final AiMediaVideoMapper videoMapper;
    private final AiMediaTranscriptMapper transcriptMapper;

    public AiMediaTranscriptService(AiMediaVideoMapper videoMapper,
                                    AiMediaTranscriptMapper transcriptMapper) {
        this.videoMapper = videoMapper;
        this.transcriptMapper = transcriptMapper;
    }

    /** 查询视频最新一次处理产生的转写全文。 */
    public AiMediaTranscriptRes detail(Long videoId) {
        if (videoMapper.selectById(videoId) == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_NOT_FOUND);
        }

        AiMediaTranscriptEntity transcript = transcriptMapper.selectLatestByVideoId(videoId);
        if (transcript == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_TRANSCRIPT_NOT_FOUND);
        }

        AiMediaTranscriptRes result = new AiMediaTranscriptRes();
        result.setId(transcript.getId());
        result.setVideoId(transcript.getVideoId());
        result.setLanguage(transcript.getLanguage());
        result.setFullText(transcript.getFullText());
        result.setDurationMs(transcript.getDurationMs());
        result.setUpdatedAt(transcript.getUpdatedAt());
        return result;
    }

    /** 分页查询视频最新转写中的分句。 */
    public PageResult<AiMediaTranscriptUtteranceRes> utterancePage(Long videoId,
                                                                   Integer pageNum,
                                                                   Integer pageSize) {
        if (videoMapper.selectById(videoId) == null) {
            throw AiMediaErrors.of(AiMediaErrorCode.MEDIA_VIDEO_NOT_FOUND);
        }

        long total = transcriptMapper.countUtterances(videoId);
        List<AiMediaTranscriptUtteranceRes> records = total == 0
                ? Collections.emptyList()
                : transcriptMapper.selectUtterancePage(videoId, (pageNum - 1) * pageSize, pageSize);
        return new PageResult<>(total, pageNum, pageSize, records);
    }
}
