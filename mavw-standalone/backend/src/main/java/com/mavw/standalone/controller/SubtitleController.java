package com.mavw.standalone.controller;

import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mavw.standalone.proxy.PythonProxyService;

/**
 * 字幕工作台接口：选项 / 构建 / 历史 / 删除 / 取文件，全部转发 Python。
 */
@RestController
@RequestMapping("/api/subtitle")
public class SubtitleController {

    private final PythonProxyService proxy;

    public SubtitleController(PythonProxyService proxy) {
        this.proxy = proxy;
    }

    @GetMapping("/options")
    public ResponseEntity<byte[]> options() {
        return proxy.exchange(HttpMethod.GET, "/api/subtitle/options");
    }

    @PostMapping("/build")
    public ResponseEntity<byte[]> build(@RequestBody Map<String, Object> body) {
        return proxy.exchange(HttpMethod.POST, "/api/subtitle/build", body);
    }

    @GetMapping("/history")
    public ResponseEntity<byte[]> history(@RequestParam(defaultValue = "40") int limit) {
        return proxy.exchange(HttpMethod.GET, "/api/subtitle/history?limit=" + limit);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<byte[]> delete(@PathVariable String id) {
        return proxy.exchange(HttpMethod.DELETE, "/api/subtitle/" + id);
    }

    @GetMapping("/files/{name}")
    public ResponseEntity<byte[]> file(@PathVariable String name) {
        return proxy.exchange(HttpMethod.GET, "/api/subtitle/files/" + name);
    }
}
