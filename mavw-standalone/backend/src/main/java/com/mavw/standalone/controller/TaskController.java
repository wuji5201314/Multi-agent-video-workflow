package com.mavw.standalone.controller;

import java.util.Map;

import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mavw.standalone.proxy.PythonProxyService;

/**
 * 视频管线任务接口：列表 / 详情 / 前进 / GATE 决策 / 素材图 / 产物，全部转发 Python。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final PythonProxyService proxy;

    public TaskController(PythonProxyService proxy) {
        this.proxy = proxy;
    }

    @GetMapping
    public ResponseEntity<byte[]> list(@RequestParam(defaultValue = "30") int limit) {
        return proxy.exchange(HttpMethod.GET, "/api/tasks?limit=" + limit);
    }

    @PostMapping
    public ResponseEntity<byte[]> create(@RequestBody Map<String, Object> body) {
        return proxy.exchange(HttpMethod.POST, "/api/tasks", body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> detail(@PathVariable String id) {
        return proxy.exchange(HttpMethod.GET, "/api/tasks/" + id);
    }

    @PostMapping("/{id}/advance")
    public ResponseEntity<byte[]> advance(@PathVariable String id,
                                          @RequestParam(required = false) Boolean auto) {
        String path = "/api/tasks/" + id + "/advance";
        if (auto != null) {
            path += "?auto=" + auto;
        }
        return proxy.exchange(HttpMethod.POST, path, Map.of());
    }

    @PostMapping("/{id}/gates/{step}")
    public ResponseEntity<byte[]> gate(@PathVariable String id, @PathVariable String step,
                                       @RequestBody Map<String, Object> body) {
        return proxy.exchange(HttpMethod.POST, "/api/tasks/" + id + "/gates/" + step, body);
    }

    @GetMapping("/{id}/materials/{index}")
    public ResponseEntity<byte[]> material(@PathVariable String id, @PathVariable String index) {
        return proxy.exchange(HttpMethod.GET, "/api/tasks/" + id + "/materials/" + index);
    }

    @GetMapping("/{id}/artifacts/{aid}")
    public ResponseEntity<byte[]> artifact(@PathVariable String id, @PathVariable String aid) {
        return proxy.exchange(HttpMethod.GET, "/api/tasks/" + id + "/artifacts/" + aid);
    }
}
