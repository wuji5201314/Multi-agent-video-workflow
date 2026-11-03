package com.mavw.standalone.config;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局配置：
 * <ul>
 *   <li>{@link RestClient}：转发到 Python 渲染引擎，可配置连接/读取超时；</li>
 *   <li>CORS：放行 Vite dev server（前端独立跑在 5173）；</li>
 * </ul>
 */
@Configuration
public class AppConfig implements WebMvcConfigurer {

    @Value("${mavw.python-base-url:http://127.0.0.1:8010}")
    private String pythonBaseUrl;

    @Value("${mavw.proxy.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${mavw.proxy.request-timeout-ms:600000}")
    private long requestTimeoutMs;

    @Value("${mavw.cors-allowed-origins:http://127.0.0.1:5173}")
    private List<String> corsAllowedOrigins;

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
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsAllowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
