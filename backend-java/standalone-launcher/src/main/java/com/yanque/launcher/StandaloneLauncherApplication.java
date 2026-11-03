package com.yanque.launcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * standalone-launcher 启动类。
 *
 * <p>在 IDEA 里直接对 main 方法 Run 即可。启动后：
 * <ul>
 *   <li>访问 {@code http://127.0.0.1:8080/} 打开前端页面（静态页来自 ../frontend）；</li>
 *   <li>{@code /api/**} 会被反向代理到 Python 后端（默认 {@code http://127.0.0.1:8010}）。</li>
 * </ul>
 *
 * <p>请先启动 Python 后端（.run/后端·Python服务.run.xml），否则接口会返回 502。
 */
@SpringBootApplication
public class StandaloneLauncherApplication {

    public static void main(String[] args) {
        SpringApplication.run(StandaloneLauncherApplication.class, args);
    }
}
