package com.yanque.aimedia.config;

import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.yanque.common.config.TosProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 媒体模块的 TOS 客户端。
 * <p>
 * 目标项目原有的 {@code TosPresignService} 在内部自建客户端，不对外暴露
 * {@code TOSV2} Bean；媒体模块需要直接上传音频和视频切片，这里补一个
 * 模块内共享的客户端 Bean。
 */
@Configuration
public class AiMediaTosConfig {

    @Bean(destroyMethod = "close")
    public TOSV2 aiMediaTosClient(TosProperties properties) {
        return new TOSV2ClientBuilder().build(properties.getRegion(), properties.getEndpoint(),
                properties.getAccessKey(), properties.getSecretKey());
    }
}
