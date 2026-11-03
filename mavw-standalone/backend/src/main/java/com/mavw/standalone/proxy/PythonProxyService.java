package com.mavw.standalone.proxy;

import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * 把任意 HTTP 调用转发给 Python 渲染引擎。
 *
 * <p>返回原始字节 + 状态码 + Content-Type，Controller 直接透传给前端；
 * Python 不可达时抛 {@link PythonUnavailableException}（由 GlobalExceptionHandler 转 502）。
 */
@Service
public class PythonProxyService {

    private final RestClient client;

    public PythonProxyService(RestClient pythonRestClient) {
        this.client = pythonRestClient;
    }

    /** 带 JSON body 的转发（POST/PUT/PATCH）。 */
    public ResponseEntity<byte[]> exchange(HttpMethod method, String path, Object body) {
        try {
            RestClient.RequestBodySpec spec = client.method(method)
                    .uri(path)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            RestClient.RequestBodySpec withBody = (body instanceof byte[] raw)
                    ? spec.body(raw)
                    : spec.body(body == null ? Map.of() : body);
            return withBody.exchange((req, res) -> ResponseEntity
                    .status(res.getStatusCode())
                    .headers(sanitize(res.getHeaders()))
                    .body(res.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            throw new PythonUnavailableException(path, e);
        }
    }

    /** 无 body 的转发（GET/DELETE）。 */
    public ResponseEntity<byte[]> exchange(HttpMethod method, String path) {
        try {
            return client.method(method).uri(path)
                    .exchange((req, res) -> ResponseEntity
                            .status(res.getStatusCode())
                            .headers(sanitize(res.getHeaders()))
                            .body(res.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            throw new PythonUnavailableException(path, e);
        }
    }

    /** 原始字节 + 原始 content-type 的转发（用于文件上传）。 */
    public ResponseEntity<byte[]> exchangeRaw(HttpMethod method, String path, byte[] body, String contentType) {
        try {
            org.springframework.http.MediaType mt = (contentType == null || contentType.isBlank())
                    ? org.springframework.http.MediaType.APPLICATION_OCTET_STREAM
                    : org.springframework.http.MediaType.parseMediaType(contentType);
            return client.method(method).uri(path)
                    .contentType(mt)
                    .body(body)
                    .exchange((req, res) -> ResponseEntity
                            .status(res.getStatusCode())
                            .headers(sanitize(res.getHeaders()))
                            .body(res.getBody().readAllBytes()));
        } catch (ResourceAccessException e) {
            throw new PythonUnavailableException(path, e);
        }
    }

    private org.springframework.http.HttpHeaders sanitize(org.springframework.http.HttpHeaders in) {
        org.springframework.http.HttpHeaders out = new org.springframework.http.HttpHeaders();
        in.forEach((k, values) -> {
            String lk = k.toLowerCase();
            // 跳过 hop-by-hop 与长度相关头，避免与容器重新计算冲突
            if (lk.equals("connection") || lk.equals("keep-alive") || lk.equals("transfer-encoding")
                    || lk.equals("content-length") || lk.equals("content-encoding")) {
                return;
            }
            out.put(k, values);
        });
        return out;
    }
}
