package com.yanque.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 把 {@code /api/**} 反向代理到 Python 后端（默认 http://127.0.0.1:8010）。
 *
 * <p>这样 IDEA 里只起这一个 Java 进程，浏览器访问 {@code http://127.0.0.1:8080/} 就能看到前端，
 * 前端的 /api 请求由本类透传到 Python，前端无需改任何地址。
 *
 * <p>仅透传方法、查询串、必要请求头与请求体；响应状态码、Content-Type 与字节体原样回写。
 * 上传大文件时走同一通道（不使用缓冲上限外的东西）。Python 未启动时返回 502。
 */
@RestController
public class ApiProxyController {

    private static final Set<String> HOP_HEADERS = Set.of(
            "connection", "keep-alive", "transfer-encoding", "te", "trailer",
            "proxy-authorization", "proxy-authenticate", "upgrade", "host",
            "content-length", "accept-encoding");

    @Value("${launcher.python-base:http://127.0.0.1:8010}")
    private String pythonBase;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        String target = pythonBase;
        if (target.endsWith("/")) {
            target = target.substring(0, target.length() - 1);
        }
        String path = request.getRequestURI();           // 含 /api/...
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) {
            path = path + "?" + query;
        }

        byte[] body;
        try {
            body = request.getInputStream().readAllBytes();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(("读取请求体失败: " + e.getMessage()).getBytes());
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(target + path))
                .timeout(Duration.ofSeconds(600));

        String method = request.getMethod();
        HttpRequest.BodyPublisher publisher = (body.length > 0)
                ? HttpRequest.BodyPublishers.ofByteArray(body)
                : HttpRequest.BodyPublishers.noBody();

        builder.method(method, publisher);

        // 透传请求头（跳过 hop-by-hop 与长度相关）
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (HOP_HEADERS.contains(name.toLowerCase())) {
                return;
            }
            Collections.list(request.getHeaders(name)).forEach(value ->
                    builder.header(name, value));
        });

        HttpResponse<byte[]> response;
        try {
            response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String msg = "Python 后端不可达（" + target + "）：" + e.getMessage()
                    + "。请先启动 .run/后端·Python服务.run.xml。";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8));
            return new ResponseEntity<>(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    headers, HttpStatus.BAD_GATEWAY);
        }

        HttpHeaders outHeaders = new HttpHeaders();
        response.headers().map().forEach((k, values) -> {
            if (HOP_HEADERS.contains(k.toLowerCase())) {
                return;
            }
            outHeaders.put(k, values);
        });

        return new ResponseEntity<>(response.body(), outHeaders,
                HttpStatus.valueOf(response.statusCode()));
    }
}
