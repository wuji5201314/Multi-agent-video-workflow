package com.mavw.standalone.proxy;

import org.springframework.web.client.ResourceAccessException;

/**
 * Python 渲染引擎不可达时抛出，由兜底异常处理器转成 502 + 中文提示。
 */
public class PythonUnavailableException extends RuntimeException {

    private final String path;

    public PythonUnavailableException(String path, ResourceAccessException cause) {
        super("Python 渲染引擎不可达（path=" + path + "）: " + cause.getMessage(), cause);
        this.path = path;
    }

    public String getPath() {
        return path;
    }
}
