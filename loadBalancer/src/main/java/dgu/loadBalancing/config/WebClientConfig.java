package dgu.loadBalancing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;

/**
 * HTTP 클라이언트 설정
 */
@Configuration
public class WebClientConfig {
    
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(1024 * 1024)) // 1MB
                .build();
    }
    
    /**
     * 타임아웃 설정
     */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(3);
}
