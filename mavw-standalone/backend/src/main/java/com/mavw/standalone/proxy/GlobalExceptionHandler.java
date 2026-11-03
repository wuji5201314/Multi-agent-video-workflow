package com.mavw.standalone.proxy;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 兜底异常处理：Python 不可达 → 502；其他未捕获异常 → 500，统一 JSON 结构，方便前端提示。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PythonUnavailableException.class)
    public ResponseEntity<Map<String, Object>> onPythonDown(PythonUnavailableException e) {
        String msg = "Python 渲染引擎未启动或不可达。请先启动 Python 服务"
                + "（backend-python: .venv\\Scripts\\python.exe run.py --port 8010）。";
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(Map.of("error", "python_unavailable", "message", msg, "path", e.getPath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onError(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(Map.of("error", "internal_error", "message",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }
}
