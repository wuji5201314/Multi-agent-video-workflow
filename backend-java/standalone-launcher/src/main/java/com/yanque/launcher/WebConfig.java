package com.yanque.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把 frontend 目录当作静态资源根，并让它对 {@code /**} 生效（index.html 自动作为首页）。
 *
 * <p>frontend 目录默认取「本启动工程所在目录的上一级 + /frontend」，
 * 也可用 {@code launcher.frontend-dir} 覆盖（绝对路径）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${launcher.frontend-dir:}")
    private String frontendDir;

    private Path resolveFrontend() throws IOException {
        if (frontendDir != null && !frontendDir.isBlank()) {
            return Paths.get(frontendDir).toAbsolutePath().normalize();
        }
        // user.dir 一般是 standalone-launcher 模块目录（IDEA）或仓库根（命令行 mvn 在模块内）
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        // 优先：cwd/../frontend；退化：cwd/frontend
        Path a = cwd.resolve("../frontend").normalize();
        if (a.toFile().isDirectory()) {
            return a;
        }
        Path b = cwd.resolve("frontend").normalize();
        if (b.toFile().isDirectory()) {
            return b;
        }
        return a;
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 根路径与无扩展名的视图路径都落到 index.html（零构建前端就是单页 index.html）
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String base;
        try {
            base = resolveFrontend().toUri().toString();
        } catch (IOException e) {
            base = Paths.get("../frontend").toAbsolutePath().normalize().toUri().toString();
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(base)
                .setCachePeriod(0);
    }
}
