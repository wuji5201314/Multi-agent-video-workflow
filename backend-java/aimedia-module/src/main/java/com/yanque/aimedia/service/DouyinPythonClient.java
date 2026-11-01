package com.yanque.aimedia.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.yanque.aimedia.config.DouyinCollectionProperties;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调用 YanQue-AI 的抖音浏览器采集接口，并将 JSON 结果转换为 Java 领域对象。 */
@Component
public class DouyinPythonClient {
    /** YanQue-AI 服务根地址。 */
    private final String baseUrl;
    /** 单目标数量、总数量和 HTTP 超时配置。 */
    private final DouyinCollectionProperties properties;

    public DouyinPythonClient(
            @Value("${yanque-ai.base-url:http://localhost:8000}") String baseUrl,
            DouyinCollectionProperties properties) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.properties = properties;
    }

    /** 提交启用的关键词和账号，获取完成平台视频 ID 去重后的采集结果。 */
    public CollectResponse collect(List<String> keywords, List<String> accounts) {
        return collect(keywords, accounts,
                properties.getMaxResultsPerTarget(), properties.getMaxVideos());
    }

    /** 实时发现可按本次请求数量采集，不受定时任务默认总量影响。 */
    public CollectResponse collect(List<String> keywords, List<String> accounts,
                                   int maxResultsPerTarget, int maxVideos) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("keywords", keywords);
        payload.put("accounts", accounts);
        payload.put("max_results_per_target", maxResultsPerTarget);
        payload.put("max_videos", maxVideos);

        try (HttpResponse response = HttpRequest.post(baseUrl + "/ai/douyin/collect")
                .contentType("application/json; charset=UTF-8")
                .timeout(properties.getHttpTimeoutMillis())
                .body(JSON.toJSONString(payload))
                .execute()) {
            String body = response.body();
            if (!response.isOk()) {
                throw new IllegalStateException("抖音采集服务失败: status=" + response.getStatus()
                        + ", body=" + body);
            }
            JSONObject root = JSON.parseObject(body);
            return new CollectResponse(parseVideos(root.getJSONArray("videos")),
                    parseErrors(root.getJSONArray("errors")));
        } catch (Exception exception) {
            throw new IllegalStateException("调用抖音采集服务失败", exception);
        }
    }

    /** 将 Python 返回的视频数组转换为强类型列表。 */
    private List<CollectedVideo> parseVideos(JSONArray rows) {
        if (rows == null) {
            return List.of();
        }
        List<CollectedVideo> videos = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.getJSONObject(index);
            videos.add(new CollectedVideo(
                    row.getString("douyin_video_id"),
                    row.getString("video_url"),
                    row.getString("title"),
                    row.getString("description"),
                    row.getString("author_sec_user_id"),
                    row.getString("author_name"),
                    row.getString("author_url"),
                    row.getString("cover_url"),
                    row.getInteger("duration_ms"),
                    parseTime(row.getString("publish_time")),
                    row.getList("matched_keywords", String.class),
                    row.getLong("like_count")
            ));
        }
        return List.copyOf(videos);
    }

    /** 将 Python 返回的目标级错误转换为强类型列表。 */
    private List<TargetError> parseErrors(JSONArray rows) {
        if (rows == null) {
            return List.of();
        }
        List<TargetError> errors = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            JSONObject row = rows.getJSONObject(index);
            errors.add(new TargetError(row.getString("target_type"), row.getString("target_value"),
                    row.getString("message")));
        }
        return List.copyOf(errors);
    }

    /** 将带时区的 ISO 发布时间转换为服务器本地时间。 */
    private LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** Python 一轮采集的完整返回结果。 */
    public record CollectResponse(List<CollectedVideo> videos, List<TargetError> errors) {
    }

    /** Python 从公开页面解析出的一条抖音视频及当前互动数据。 */
    public record CollectedVideo(
            String douyinVideoId,
            String videoUrl,
            String title,
            String description,
            String authorSecUserId,
            String authorName,
            String authorUrl,
            String coverUrl,
            Integer durationMs,
            LocalDateTime publishTime,
            List<String> matchedKeywords,
            Long likeCount) {
    }

    /** 单个关键词或账号采集失败信息。 */
    public record TargetError(String targetType, String targetValue, String message) {
    }
}
