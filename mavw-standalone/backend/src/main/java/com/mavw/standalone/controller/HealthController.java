package com.mavw.standalone.controller;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mavw.standalone.proxy.PythonProxyService;

/**
 * 健康检查：转发 Python 的 /api/health，同时标注本 Java 层已就绪。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final PythonProxyService proxy;

    public HealthController(PythonProxyService proxy) {
        this.proxy = proxy;
    }

    @GetMapping("/health")
    public ResponseEntity<byte[]> health() {
        return proxy.exchange(HttpMethod.GET, "/api/health");
    }
}
