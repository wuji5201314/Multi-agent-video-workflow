package com.yanque.aimedia.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yanque.aimedia.pojo.entity.AiMediaProcessTaskEntity;
import com.yanque.aimedia.pojo.entity.AiMediaTranscriptEntity;
import com.yanque.aimedia.pojo.entity.AiMediaVideoClipEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 把大模型选择的分句范围转换为可落库的视频切片方案。 */
@Service
public class AiMediaVideoClipService {
    public static final int MAX_SEGMENTS = 3;
    private static final long MIN_DURATION_MILLIS = 60_000L;
    private static final long MAX_DURATION_MILLIS = 300_000L;

    /**
     * 时间范围只取自原始分句，大模型不能直接决定毫秒值。
     * utterance_no 使用从 1 开始的数组序号，不需要在转写表新增字段。
     */
    public List<AiMediaVideoClipEntity> buildClips(
            AiMediaProcessTaskEntity task,
            AiMediaTranscriptEntity transcript,
            List<AiMediaMaterialPythonClient.MaterialSegment> segments) {
        if (segments.size() > MAX_SEGMENTS) {
            throw new IllegalArgumentException("视频素材片段不能超过 " + MAX_SEGMENTS + " 条");
        }

        JSONArray utterances = JSON.parseArray(transcript.getUtterancesJson());
        List<AiMediaVideoClipEntity> clips = new ArrayList<>(segments.size());
        for (int index = 0; index < segments.size(); index++) {
            AiMediaMaterialPythonClient.MaterialSegment segment = segments.get(index);
            int startIndex = segment.startUtteranceNo() - 1;
            int endIndex = segment.endUtteranceNo() - 1;
            if (startIndex < 0 || endIndex < startIndex || endIndex >= utterances.size()) {
                throw new IllegalArgumentException("视频素材片段的分句范围无效");
            }

            long startMs = utterances.getJSONObject(startIndex).getLongValue("start_time");
            long endMs = utterances.getJSONObject(endIndex).getLongValue("end_time");
            long durationMs = endMs - startMs;
            if (durationMs < MIN_DURATION_MILLIS || durationMs > MAX_DURATION_MILLIS) {
                throw new IllegalArgumentException("视频素材片段时长必须在 1 到 5 分钟之间");
            }

            AiMediaVideoClipEntity clip = new AiMediaVideoClipEntity();
            clip.setVideoId(task.getVideoId());
            clip.setTaskId(task.getId());
            clip.setTranscriptId(transcript.getId());
            clip.setSegmentNo(index + 1);
            clip.setStartMs(startMs);
            clip.setEndMs(endMs);
            clip.setDurationMs(durationMs);
            clip.setTitle(segment.title());
            clip.setSummary(segment.summary());
            clip.setCoreViewpoint(segment.coreViewpoint());
            clip.setClipType(segment.clipType());
            clip.setValueReason(segment.valueReason());
            clip.setTagsJson(JSON.toJSONString(segment.tags()));
            clip.setSpeakersJson(JSON.toJSONString(segment.speakers()));
            clip.setTranscriptText(joinTranscript(utterances, startIndex, endIndex));
            clips.add(clip);
        }
        return List.copyOf(clips);
    }

    /** 拼接切片范围内的整理后对话，同时保留角色，供页面展示和后续 RAG 使用。 */
    private String joinTranscript(JSONArray utterances, int startIndex, int endIndex) {
        StringBuilder text = new StringBuilder();
        for (int index = startIndex; index <= endIndex; index++) {
            JSONObject utterance = utterances.getJSONObject(index);
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(utterance.getString("speaker"))
                    .append("：")
                    .append(utterance.getString("text"));
        }
        return text.toString();
    }
}
