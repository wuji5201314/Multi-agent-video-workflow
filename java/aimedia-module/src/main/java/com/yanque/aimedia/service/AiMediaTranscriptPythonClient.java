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

import java.util.LinkedHashMap;
import java.util.Map;

/** 调用 YanQue-AI 视频转写整理接口。 */
@Component
public class AiMediaTranscriptPythonClient {
    private static final Logger log = LoggerFactory.getLogger(AiMediaTranscriptPythonClient.class);
    // 长视频会在 Python 中顺序分批整理，Java 侧需要等待所有批次处理完成。
    public static final int REQUEST_TIMEOUT_MILLIS = 1_800_000;

    private final String baseUrl;

    public AiMediaTranscriptPythonClient(
            @Value("${yanque-ai.base-url:http://localhost:8000}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    /**
     * 把 ASR 精简分句交给 Python，返回大模型整理后的全文和对话 JSON。
     * 日志只记录数量，不打印完整转写内容。
     */
    public RefinementResult refine(Long videoId, String utterancesJson) {
        JSONArray utterances = JSON.parseArray(utterancesJson);
        String url = baseUrl + "/ai/media-transcripts/refine";
        String requestBody = JSON.toJSONString(buildRequest(videoId, utterances));
        log.info("调用AI视频转写整理服务: videoId={}, utteranceCount={}",
                videoId, utterances.size());

        try (HttpResponse response = HttpRequest.post(url)
                .contentType("application/json; charset=UTF-8")
                .timeout(REQUEST_TIMEOUT_MILLIS)
                .body(requestBody)
                .execute()) {
            String body = response.body();
            if (!response.isOk()) {
                throw new IllegalStateException("AI视频转写整理失败: status="
                        + response.getStatus() + ", body=" + body);
            }

            JSONObject result = JSON.parseObject(body);
            JSONArray refinedUtterances = result.getJSONArray("utterances");
            if (refinedUtterances == null || refinedUtterances.isEmpty()) {
                throw new IllegalStateException("AI视频转写整理结果为空");
            }

            log.info("AI视频转写整理完成: videoId={}, dialogueCount={}",
                    videoId, refinedUtterances.size());
            return new RefinementResult(
                    result.getString("full_text"),
                    refinedUtterances.toJSONString()
            );
        } catch (Exception exception) {
            log.warn("AI视频转写整理调用异常: videoId={}", videoId, exception);
            throw new IllegalStateException("AI视频转写整理调用失败", exception);
        }
    }

    private Map<String, Object> buildRequest(Long videoId, JSONArray utterances) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("video_id", videoId);
        request.put("utterances", utterances);
        return request;
    }

    /** YanQue-AI 返回的精简整理结果。 */
    public record RefinementResult(String fullText, String utterancesJson) {
    }
}
