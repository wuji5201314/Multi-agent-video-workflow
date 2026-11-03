package com.mavw.standalone.controller;

import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.mavw.standalone.proxy.PythonProxyService;

/**
 * 图片工作台接口：选项 / 生成 / 上传 / 识别 / 历史 / 删除 / 取文件，全部转发 Python。
 */
@RestController
@RequestMapping("/api/images")
public class ImageController {

    private final PythonProxyService proxy;

    public ImageController(PythonProxyService proxy) {
        this.proxy = proxy;
    }

    @GetMapping("/options")
    public ResponseEntity<byte[]> options() {
        return proxy.exchange(HttpMethod.GET, "/api/images/options");
    }

    @PostMapping("/generate")
    public ResponseEntity<byte[]> generate(@RequestBody Map<String, Object> body) {
        return proxy.exchange(HttpMethod.POST, "/api/images/generate", body);
    }

    @PostMapping("/analyze")
    public ResponseEntity<byte[]> analyze(@RequestBody Map<String, Object> body) {
        return proxy.exchange(HttpMethod.POST, "/api/images/analyze", body);
    }

    /** 上传参考图：前端以原始二进制 + Content-Type 提交，直接透传字节与类型。 */
    @PostMapping("/upload")
    public ResponseEntity<byte[]> upload(@RequestPart("file") MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String path = "/api/images/upload";
            // 用一个轻量包装把原始字节 + 原始 content-type 传给 Python
            return proxyRaw(bytes, file.getContentType(), path);
        } catch (java.io.IOException e) {
            throw new RuntimeException("读取上传文件失败: " + e.getMessage(), e);
        }
    }

    @GetMapping("/history")
    public ResponseEntity<byte[]> history(@RequestParam(defaultValue = "40") int limit) {
        return proxy.exchange(HttpMethod.GET, "/api/images/history?limit=" + limit);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<byte[]> delete(@PathVariable String id) {
        return proxy.exchange(HttpMethod.DELETE, "/api/images/" + id);
    }

    @GetMapping("/files/{name}")
    public ResponseEntity<byte[]> file(@PathVariable String name) {
        return proxy.exchange(HttpMethod.GET, "/api/images/files/" + name);
    }

    @GetMapping("/uploads/{name}")
    public ResponseEntity<byte[]> uploadFile(@PathVariable String name) {
        return proxy.exchange(HttpMethod.GET, "/api/images/uploads/" + name);
    }

    /** 原始字节透传到 Python（用于上传，保留原始 content-type）。 */
    private ResponseEntity<byte[]> proxyRaw(byte[] bytes, String contentType, String path) {
        // 复用 proxy 的 JSON 通道不合用，这里直接用底层 exchange 变体
        return proxy.exchangeRaw(HttpMethod.POST, path, bytes, contentType);
    }
}
