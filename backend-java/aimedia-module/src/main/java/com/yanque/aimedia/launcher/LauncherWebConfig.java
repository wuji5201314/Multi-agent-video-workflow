package com.yanque.aimedia.launcher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 启动器 Web 配置：
 * <ul>
 *   <li>{@link RestClient}：把 /api 转发给 Python 渲染引擎；</li>
 *   <li>静态资源：托管 backend-java/frontend 下的前端页面（零构建）；</li>
 *   <li>CORS：放行本地前端来源。</li>
 * </ul>
 */
@Configuration
public class LauncherWebConfig implements WebMvcConfigurer {

    @Value("${mavw.python-base-url:http://127.0.0.1:8010}")
    private String pythonBaseUrl;

    @Value("${mavw.proxy.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${mavw.proxy.request-timeout-ms:600000}")
    private long requestTimeoutMs;

    /** frontend 目录：默认 {项目根}/backend-java/frontend，可通过 mavw.frontend-dir 覆盖。 */
    @Value("${mavw.frontend-dir:}")
    private String frontendDir;

    @Bean
    public RestClient pythonRestClient() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(requestTimeoutMs));
        return RestClient.builder()
                .baseUrl(pythonBaseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = resolveFrontendLocation();
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .setCachePeriod(0);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    private String resolveFrontendLocation() {
        if (frontendDir != null && !frontendDir.isBlank()) {
            return "file:" + frontendDir.replace("\\", "/") + "/";
        }
        // 工作目录通常是 aimedia-module，前端在 ../../frontend
        return "file:../frontend/";
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
