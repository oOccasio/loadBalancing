package dgu.loadBalancing.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger 설정 클래스
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Load Balancer API")
                        .description("머신러닝 기반 지능형 로드밸런싱 시스템")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("DGU LoadBalancer Team")
                                .email("loadbalancer@dgu.ac.kr")));
    }
}
