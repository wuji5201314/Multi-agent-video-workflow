package com.yanque.aimedia.service;

import com.yanque.aimedia.pojo.entity.AiMediaVideoClipEntity;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调用 YanQue-AI，将视频素材文本写入独立的 Milvus Collection。 */
@Component
public class AiMediaMaterialIndexPythonClient {
    private static final Logger log = LoggerFactory.getLogger(AiMediaMaterialIndexPythonClient.class);
    public static final int REQUEST_TIMEOUT_MILLIS = 120_000;

    private final String baseUrl;

    public AiMediaMaterialIndexPythonClient(
            @Value("${yanque-ai.base-url:http://localhost:8000}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
    }

    /**
     * 按任务覆盖写入素材索引。
     * 只发送检索需要的文字和筛选元数据，不发送 object_key 或临时播放 URL。
     */
    public int index(Long taskId, List<AiMediaVideoClipEntity> clips) {
        String url = baseUrl + "/ai/media-materials/index";
        String requestBody = JSON.toJSONString(buildRequest(taskId, clips));
        log.info("调用AI视频素材索引服务: taskId={}, materialCount={}", taskId, clips.size());

        try (HttpResponse response = HttpRequest.post(url)
                .contentType("application/json; charset=UTF-8")
                .timeout(REQUEST_TIMEOUT_MILLIS)
                .body(requestBody)
                .execute()) {
            String body = response.body();
            if (!response.isOk()) {
                throw new IllegalStateException("AI视频素材索引失败: status="
                        + response.getStatus() + ", body=" + body);
            }
            int indexedCount = JSON.parseObject(body).getIntValue("indexed_count");
            if (indexedCount != clips.size()) {
                throw new IllegalStateException("AI视频素材索引数量不一致: expected="
                        + clips.size() + ", actual=" + indexedCount);
            }
            log.info("AI视频素材索引完成: taskId={}, indexedCount={}", taskId, indexedCount);
            return indexedCount;
        } catch (Exception exception) {
            log.warn("AI视频素材索引调用异常: taskId={}", taskId, exception);
            throw new IllegalStateException("AI视频素材索引调用失败", exception);
        }
    }

    private Map<String, Object> buildRequest(Long taskId, List<AiMediaVideoClipEntity> clips) {
        List<Map<String, Object>> materials = new ArrayList<>(clips.size());
        for (AiMediaVideoClipEntity clip : clips) {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("material_id", clip.getId());
            material.put("video_id", clip.getVideoId());
            material.put("title", clip.getTitle());
            material.put("summary", clip.getSummary());
            material.put("core_viewpoint", clip.getCoreViewpoint());
            material.put("clip_type", clip.getClipType());
            material.put("value_reason", clip.getValueReason());
            material.put("tags", parseStringArray(clip.getTagsJson()));
            material.put("speakers", parseStringArray(clip.getSpeakersJson()));
            material.put("transcript_text", clip.getTranscriptText());
            material.put("duration_ms", clip.getDurationMs());
            materials.add(material);
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("task_id", taskId);
        request.put("materials", materials);
        return request;
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JSONArray array = JSON.parseArray(json);
        return array.toJavaList(String.class);
    }
}
