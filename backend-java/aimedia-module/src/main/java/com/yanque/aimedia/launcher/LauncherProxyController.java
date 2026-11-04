package com.yanque.aimedia.launcher;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 把任意 /api/** 请求反向代理到 Python 渲染引擎。
 *
 * <p>不区分具体接口（健康检查、图片、配音、字幕、视频任务全部透传），
 * 以最少代码保留 Python 侧全部能力。Python 不可达时返回 502 + 中文提示。
 */
@RestController
public class LauncherProxyController {

    private final RestClient client;

    public LauncherProxyController(RestClient pythonRestClient) {
        this.client = pythonRestClient;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            path += "?" + query;
        }
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        try {
            byte[] payload = body == null ? new byte[0] : body;
            String contentType = request.getContentType();
            MediaType mt = (contentType == null || contentType.isBlank())
                    ? MediaType.APPLICATION_JSON
                    : MediaType.parseMediaType(contentType);

            RestClient.RequestBodySpec spec = client.method(method).uri(path).contentType(mt);
            if (payload.length > 0) {
                spec.body(payload);
            }
            return spec.exchange((req, res) -> ResponseEntity
                    .status(res.getStatusCode())
                    .headers(sanitize(res.getHeaders()))
                    .body(res.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            return ResponseEntity.status(502)
                    .contentType(new MediaType(MediaType.APPLICATION_JSON, java.nio.charset.StandardCharsets.UTF_8))
                    .body(("{\"error\":\"python_unavailable\",\"message\":\"Python 渲染引擎未启动或不可达，"
                            + "请先启动 backend-python (run.py --port 8010)\",\"path\":\"" + path + "\"}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private org.springframework.http.HttpHeaders sanitize(org.springframework.http.HttpHeaders in) {
        org.springframework.http.HttpHeaders out = new org.springframework.http.HttpHeaders();
        in.forEach((k, values) -> {
            String lk = k.toLowerCase();
            if (lk.equals("connection") || lk.equals("keep-alive") || lk.equals("transfer-encoding")
                    || lk.equals("content-length") || lk.equals("content-encoding")) {
                return;
            }
            out.put(k, values);
        });
        return out;
    }
}
