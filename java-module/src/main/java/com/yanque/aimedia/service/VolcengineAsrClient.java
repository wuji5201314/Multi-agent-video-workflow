package com.yanque.aimedia.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.yanque.aimedia.config.VolcengineAsrProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 火山引擎大模型录音文件识别闲时版客户端。 */
@Slf4j
@Component
public class VolcengineAsrClient {
    private static final String SUCCESS_CODE = "20000000";
    private static final String PROCESSING_CODE = "20000001";

    private final VolcengineAsrProperties properties;
    private final ObjectMapper objectMapper;

    public VolcengineAsrClient(VolcengineAsrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交闲时识别任务。
     *
     * 火山使用 X-Api-Request-Id 作为异步任务标识，后续查询继续携带该值。
     */
    public String submit(String audioUrl) {
        requireApiKey();
        String requestId = UUID.randomUUID().toString();
        String endpoint = apiBaseUrl() + "/submit";
        String requestBody = toJson(Map.of(
                "user", Map.of("uid", properties.getUserId()),
                "audio", Map.of(
                        "url", audioUrl,
                        "format", "mp3",
                        "codec", "raw",
                        "rate", 16000,
                        "bits", 16,
                        "channel", 1
                ),
                "request", Map.of(
                        "model_name", "bigmodel",
                        "enable_itn", true,
                        "enable_punc", true,
                        "enable_ddc", false,
                        "enable_speaker_info", true,
                        "show_utterances", true
                )
        ));

        long startedAt = System.currentTimeMillis();
        log.info("Volcengine ASR submit request, requestId={}, endpoint={}, resourceId={}",
                requestId, endpoint, properties.getResourceId());

        try (HttpResponse response = request(endpoint, requestId)
                .header("X-Api-Sequence", "-1")
                .body(requestBody)
                .execute()) {
            logResponse("submit", requestId, response, startedAt);
            ensureSuccess(response, "提交 ASR 任务");
            return requestId;
        } catch (VolcengineAsrException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VolcengineAsrException("提交 ASR 任务异常", exception);
        }
    }

    /** 根据提交时保存的任务 ID 查询一次识别结果。 */
    public QueryResult query(String providerTaskId) {
        requireApiKey();
        String endpoint = apiBaseUrl() + "/query";
        long startedAt = System.currentTimeMillis();
        log.info("Volcengine ASR query request, requestId={}, endpoint={}, resourceId={}",
                providerTaskId, endpoint, properties.getResourceId());

        try (HttpResponse response = request(endpoint, providerTaskId)
                .body("{}")
                .execute()) {
            logResponse("query", providerTaskId, response, startedAt);
            String statusCode = response.header("X-Api-Status-Code");
            if (PROCESSING_CODE.equals(statusCode)) {
                return QueryResult.processing();
            }
            ensureSuccess(response, "查询 ASR 结果");
            JsonNode body = parseJson(response.body());
            JsonNode result = body.path("result");
            return QueryResult.success(
                    result.path("text").asText(""),
                    compactUtterances(result.path("utterances")));
        } catch (VolcengineAsrException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new VolcengineAsrException("查询 ASR 结果异常", exception);
        }
    }

    /** 统一设置闲时版接口要求的认证请求头。 */
    private HttpRequest request(String endpoint, String requestId) {
        return HttpRequest.post(endpoint)
                .header("X-Api-Key", properties.getApiKey())
                .header("X-Api-Resource-Id", properties.getResourceId())
                .header("X-Api-Request-Id", requestId)
                .contentType("application/json; charset=UTF-8")
                .timeout(properties.getHttpTimeoutMillis());
    }

    /**
     * 记录火山响应中的排障信息。
     * 不记录 API Key、请求体和带签名的音频 URL，避免认证信息进入日志。
     */
    private void logResponse(String operation,
                             String requestId,
                             HttpResponse response,
                             long startedAt) {
        log.info("Volcengine ASR {} response, requestId={}, httpStatus={}, statusCode={}, "
                        + "message={}, logId={}, elapsedMs={}",
                operation,
                requestId,
                response.getStatus(),
                response.header("X-Api-Status-Code"),
                response.header("X-Api-Message"),
                response.header("X-Tt-Logid"),
                System.currentTimeMillis() - startedAt);
    }

    private void ensureSuccess(HttpResponse response, String operation) {
        String statusCode = response.header("X-Api-Status-Code");
        if (!response.isOk() || !SUCCESS_CODE.equals(statusCode)) {
            String message = response.header("X-Api-Message");
            throw new VolcengineAsrException(operation + "失败，statusCode=" + statusCode
                    + ", message=" + (message == null ? "unknown" : message));
        }
    }

    private JsonNode parseJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new VolcengineAsrException("火山 ASR 返回的不是合法 JSON", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new VolcengineAsrException("ASR 请求序列化失败", exception);
        }
    }

    /**
     * 只保存页面展示和后续视频切片需要的分句级数据。
     * 火山返回的 words 是逐字时间戳，当前业务不使用，不应占用数据库空间。
     */
    private String compactUtterances(JsonNode utterances) {
        ArrayNode compactRows = objectMapper.createArrayNode();
        if (!utterances.isArray()) {
            return compactRows.toString();
        }

        for (JsonNode utterance : utterances) {
            ObjectNode compactRow = compactRows.addObject();
            compactRow.put("text", utterance.path("text").asText(""));
            compactRow.put("start_time", utterance.path("start_time").asLong(0));
            compactRow.put("end_time", utterance.path("end_time").asLong(0));

            JsonNode speaker = utterance.path("speaker");
            if (speaker.isMissingNode() || speaker.isNull()) {
                speaker = utterance.path("additions").path("speaker");
            }
            if (!speaker.isMissingNode() && !speaker.isNull()) {
                compactRow.put("speaker", speaker.asText());
            }
        }
        return compactRows.toString();
    }

    private String apiBaseUrl() {
        return properties.getApiBaseUrl().replaceAll("/+$", "");
    }

    private void requireApiKey() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new VolcengineAsrException("YQ_VOLCENGINE_ASR_API_KEY 未配置");
        }
    }

    /** 一次查询只有“仍在处理”和“识别成功”两种正常结果。 */
    public record QueryResult(boolean completed, String text, String utterancesJson) {
        public static QueryResult processing() {
            return new QueryResult(false, null, null);
        }

        public static QueryResult success(String text, String utterancesJson) {
            return new QueryResult(true, text, utterancesJson);
        }
    }

    public static class VolcengineAsrException extends RuntimeException {
        public VolcengineAsrException(String message) {
            super(message);
        }

        public VolcengineAsrException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
