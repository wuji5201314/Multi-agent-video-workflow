package com.yanque.aimedia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * aimedia 模块的可独立启动入口。
 *
 * <p>在 IDEA 里对本类 main 方法 Run 即可（端口见 src/main/resources/application-launcher.properties，默认 8088）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>本模块原有 controller 依赖 {@code com.yanque.common.*}（来自 yq-admin 父工程，此处缺失），
 *       因此不能整包扫描。这里只扫描 {@code com.yanque.aimedia.launcher} 子包（本启动器自带的
 *       健康检查、{@code /api/**} 反向代理、前端静态托管），不扫描其它依赖 common 的类。</li>
 *   <li>{@code /api/**} 请求反向代理到 Python 渲染引擎（默认 http://127.0.0.1:8010），
 *       保留 Python 侧出图 / TTS / 字幕 / 渲染能力。</li>
 *   <li>前端静态页来自 {@code backend-java/frontend}（零构建静态资源）。</li>
 * </ul>
 *
 * <p>启动前请先运行 Python 引擎（backend-python: {@code .venv\\Scripts\\python.exe run.py --port 8010}），
 * 否则 /api 接口会返回 502。
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@ComponentScan(
        basePackages = "com.yanque.aimedia.launcher",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "com\\.yanque\\.common\\..*"
        )
)
public class MavwApplication {

    public static void main(String[] args) {
        SpringApplication.run(MavwApplication.class, args);
    }
}
