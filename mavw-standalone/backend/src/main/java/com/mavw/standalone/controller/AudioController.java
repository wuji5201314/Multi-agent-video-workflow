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
 * 配音工作台接口：音色列表 / 合成 / 历史 / 删除 / 取音频，全部转发 Python。
 */
@RestController
@RequestMapping("/api/audio")
public class AudioController {

    private final PythonProxyService proxy;

    public AudioController(PythonProxyService proxy) {
        this.proxy = proxy;
    }

    @GetMapping("/voices")
    public ResponseEntity<byte[]> voices() {
        return proxy.exchange(HttpMethod.GET, "/api/audio/voices");
    }

    @PostMapping("/tts")
    public ResponseEntity<byte[]> tts(@RequestBody Map<String, Object> body) {
        return proxy.exchange(HttpMethod.POST, "/api/audio/tts", body);
    }

    @GetMapping("/history")
    public ResponseEntity<byte[]> history(@RequestParam(defaultValue = "40") int limit) {
        return proxy.exchange(HttpMethod.GET, "/api/audio/history?limit=" + limit);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<byte[]> delete(@PathVariable String id) {
        return proxy.exchange(HttpMethod.DELETE, "/api/audio/" + id);
    }

    @GetMapping("/files/{name}")
    public ResponseEntity<byte[]> file(@PathVariable String name) {
        return proxy.exchange(HttpMethod.GET, "/api/audio/files/" + name);
    }
}
