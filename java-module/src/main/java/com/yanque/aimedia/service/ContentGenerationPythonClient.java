package com.yanque.aimedia.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.yanque.common.exception.BusinessException;
import com.yanque.aimedia.pojo.vo.reqvo.ContentGenerationContentReq;
import com.yanque.aimedia.pojo.vo.reqvo.ContentGenerationResumeReq;
import com.yanque.aimedia.pojo.vo.reqvo.ContentGenerationStartReq;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.net.SocketTimeoutException;

/**
 * YanQue-AI 内容生成工作流客户端。
 *
 * 前端只访问 Java，由本客户端负责字段转换和 Python 接口转发。
 */
@Component
public class ContentGenerationPythonClient {
    private static final int HTTP_TIMEOUT_MILLIS = 300_000;
    private static final int MATERIAL_TIMEOUT_MILLIS = 720_000;
    private static final int DEFAULT_PREVIEW_TIMEOUT_MILLIS = 1_200_000;

    /** YanQue-AI 服务根地址。 */
    private final String baseUrl;
    /** 成品渲染超时；视频成片需要比图片轮播更长的预算。 */
    private final int previewTimeoutMillis;

    public ContentGenerationPythonClient(
            @Value("${yanque-ai.base-url:http://localhost:8000}") String baseUrl,
            @Value("${ai.content-preview.render-timeout-millis:2400000}") int previewTimeoutMillis) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.previewTimeoutMillis = previewTimeoutMillis <= 0
                ? DEFAULT_PREVIEW_TIMEOUT_MILLIS : previewTimeoutMillis;
    }

    /** 启动内容生成工作流，并运行到等待人工确认选题。 */
    public JSONObject start(ContentGenerationStartReq req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("candidate_count", req.getCandidateCount());
        payload.put("source_mode", req.getSourceMode());
        payload.put("keyword", req.getKeyword());
        return post("/ai/content-generation/topics/generate", payload);
    }

    /** 恢复暂停的工作流，确认选题或携带反馈重新生成。 */
    public JSONObject resume(String conversationId, ContentGenerationResumeReq req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", req.getAction());
        payload.put("topic_id", req.getTopicId());
        payload.put("feedback", req.getFeedback());
        return post("/ai/content-generation/topics/" + conversationId + "/resume", payload);
    }

    /** 重试指定会话的资料检索节点。 */
    public JSONObject retryResearch(String conversationId) {
        return post(
                "/ai/content-generation/topics/" + conversationId + "/research/retry",
                Map.of());
    }

    /** 首次生成文章，或把修改意见交给文字内容创作 Agent。 */
    public JSONObject generateContent(String conversationId, ContentGenerationContentReq req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("feedback", req.getFeedback());
        return post(
                "/ai/content-generation/topics/" + conversationId + "/content/generate",
                payload);
    }

    /** 从文章完成后的暂停点启动素材匹配子图，失败时也用于原地重试。 */
    public JSONObject generateMaterials(String conversationId, Integer contentRevision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content_revision", contentRevision);
        return post(
                "/ai/content-generation/topics/" + conversationId + "/materials/generate",
                payload,
                MATERIAL_TIMEOUT_MILLIS);
    }

    public JSONObject regenerateMaterialImage(String conversationId, String needId,
                                               Integer contentRevision, String feedback) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content_revision", contentRevision);
        payload.put("feedback", feedback);
        return post("/ai/content-generation/topics/" + conversationId
                + "/materials/" + needId + "/regenerate", payload, MATERIAL_TIMEOUT_MILLIS);
    }

    /** 素材落库后恢复 Python 图中的内容审核节点。 */
    public JSONObject generateReview(String conversationId, Integer contentRevision) {
        return post(
                "/ai/content-generation/topics/" + conversationId + "/review/generate",
                Map.of("content_revision", contentRevision),
                HTTP_TIMEOUT_MILLIS);
    }

    /** 渲染最终视频或图文轮播；请求完全来自 Java 持久化数据。 */
    public JSONObject renderPreview(JSONObject payload) {
        return post(
                "/ai/content-generation/topics/previews/render",
                payload,
                previewTimeoutMillis);
    }

    /** 执行 JSON POST，并保留 Python 返回的工作流字段结构。 */
    private JSONObject post(String path, Map<String, Object> payload) {
        return post(path, payload, HTTP_TIMEOUT_MILLIS);
    }

    private JSONObject post(String path, Map<String, Object> payload, int timeoutMillis) {
        try (HttpResponse response = HttpRequest.post(baseUrl + path)
                .contentType("application/json; charset=UTF-8")
                .timeout(timeoutMillis)
                .body(JSON.toJSONString(payload))
                .execute()) {
            String body = response.body();
            if (!response.isOk()) {
                throw new BusinessException(response.getStatus(), parseErrorMessage(response.getStatus(), body));
            }
            return JSON.parseObject(body);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            if (isTimeout(exception)) {
                throw new BusinessException(504, "[PYTHON_REQUEST_TIMEOUT] 内容生成服务调用超时");
            }
            throw new BusinessException(502, "[PYTHON_SERVICE_UNAVAILABLE] 内容生成服务暂时不可用，请确认 YanQue-AI 已启动");
        }
    }

    private JSONObject post(String path, JSONObject payload, int timeoutMillis) {
        try (HttpResponse response = HttpRequest.post(baseUrl + path)
                .contentType("application/json; charset=UTF-8")
                .timeout(timeoutMillis)
                .body(payload.toJSONString())
                .execute()) {
            String body = response.body();
            if (!response.isOk()) {
                throw new BusinessException(response.getStatus(), parseErrorMessage(response.getStatus(), body));
            }
            return JSON.parseObject(body);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            if (isTimeout(exception)) throw new BusinessException(504, "[PYTHON_REQUEST_TIMEOUT] 成品渲染超时");
            throw new BusinessException(502, "[PYTHON_SERVICE_UNAVAILABLE] 成品渲染服务暂时不可用");
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("timed out")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /** 尽量从 FastAPI 错误响应中提取可读原因。 */
    private String parseErrorMessage(int status, String body) {
        try {
            JSONObject root = JSON.parseObject(body);
            String rootMessage = root.getString("message");
            if (rootMessage != null && !rootMessage.isBlank()) {
                String errorCode = root.getString("code");
                return errorCode == null ? rootMessage : "[" + errorCode + "] " + rootMessage;
            }
            Object detail = root.get("detail");
            if (detail instanceof JSONObject detailObject) {
                String message = detailObject.getString("message");
                String errorCode = detailObject.getString("code");
                if (message == null) {
                    return detailObject.toJSONString();
                }
                return errorCode == null ? message : "[" + errorCode + "] " + message;
            }
            if (detail != null) {
                return String.valueOf(detail);
            }
        } catch (Exception ignored) {
            // 非 JSON 响应统一使用状态码提示，避免把 HTML 错误页返回给前端。
        }
        return "内容生成服务调用失败，HTTP状态码：" + status;
    }
}
