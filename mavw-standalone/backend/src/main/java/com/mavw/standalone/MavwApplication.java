package com.mavw.standalone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Multi-Agent Video Workflow 独立后端启动类。
 *
 * <p>在 IDEA 里对 {@link #main(String[])} 直接点 Run 即可启动（端口见 application.yml，默认 8088）。
 *
 * <p>它对外提供完整的 {@code /api/**} REST 接口，内部通过 {@code RestClient} 转发给
 * Python 渲染引擎（默认 {@code http://127.0.0.1:8010}）——出图 / 配音 / 字幕 / 渲染都在 Python 侧完成。
 * 因此联调时请先启动 Python 服务；
 * 若 Python 未启动，接口会返回 502 并附带明确提示，前端可据此提示用户。
 */
@SpringBootApplication
public class MavwApplication {

    public static void main(String[] args) {
        SpringApplication.run(MavwApplication.class, args);
    }
}
