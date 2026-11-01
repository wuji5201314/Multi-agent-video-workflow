package com.yanque.aimedia.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调用 YanQue-AI 生成高价值视频切片方案。 */
@Component
public class AiMediaMaterialPythonClient {
    private static final Logger log = LoggerFactory.getLogger(AiMediaMaterialPythonClient.class);
    public static final int REQUEST_TIMEOUT_MILLIS = 1_800_000;

    private final String baseUrl;

    public AiMediaMaterialPythonClient(
            @Value("${yanque-ai.base-url:http://localhost:8000}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    /**
     * 提交整理后的对话，由 YanQue-AI 从整段视频中最多选择三条精华片段。
     * 日志只记录数量，不输出完整对话和模型返回正文。
     */
    public List<MaterialSegment> generate(Long videoId, String utterancesJson) {
        JSONArray utterances = JSON.parseArray(utterancesJson);
        String url = baseUrl + "/ai/media-materials/segments";
        String requestBody = JSON.toJSONString(buildRequest(videoId, utterances));
        log.info("调用AI视频素材分段服务: videoId={}, utteranceCount={}",
                videoId, utterances.size());

        try (HttpResponse response = HttpRequest.post(url)
                .contentType("application/json; charset=UTF-8")
                .timeout(REQUEST_TIMEOUT_MILLIS)
                .body(requestBody)
                .execute()) {
            String body = response.body();
            if (!response.isOk()) {
                throw new IllegalStateException("AI视频素材分段失败: status="
                        + response.getStatus() + ", body=" + body);
            }

            JSONArray rows = JSON.parseObject(body).getJSONArray("segments");
            if (rows == null) {
                throw new IllegalStateException("AI视频素材分段结果缺少 segments");
            }

            List<MaterialSegment> segments = new ArrayList<>(rows.size());
            for (int index = 0; index < rows.size(); index++) {
                JSONObject row = rows.getJSONObject(index);
                segments.add(new MaterialSegment(
                        row.getIntValue("start_utterance_no"),
                        row.getIntValue("end_utterance_no"),
                        row.getString("title"),
                        row.getString("summary"),
                        row.getString("core_viewpoint"),
                        row.getString("clip_type"),
                        row.getString("value_reason"),
                        row.getList("tags", String.class),
                        row.getList("speakers", String.class)
                ));
            }
            log.info("AI视频素材分段完成: videoId={}, segmentCount={}", videoId, segments.size());
            return List.copyOf(segments);
        } catch (Exception exception) {
            log.warn("AI视频素材分段调用异常: videoId={}", videoId, exception);
            throw new IllegalStateException("AI视频素材分段调用失败", exception);
        }
    }

    private Map<String, Object> buildRequest(Long videoId, JSONArray utterances) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("video_id", videoId);
        request.put("utterances", utterances);
        request.put("min_duration_seconds", 60);
        request.put("max_duration_seconds", 300);
        request.put("max_segments", 3);
        return request;
    }

    /** YanQue-AI 最终选中的一条高价值切片方案。 */
    public record MaterialSegment(
            int startUtteranceNo,
            int endUtteranceNo,
            String title,
            String summary,
            String coreViewpoint,
            String clipType,
            String valueReason,
            List<String> tags,
            List<String> speakers) {
    }
}
