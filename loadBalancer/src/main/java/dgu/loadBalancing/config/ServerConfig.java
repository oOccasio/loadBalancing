package dgu.loadBalancing.config;

import dgu.loadBalancing.model.Server;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Arrays;
import java.util.List;

/**
 * 백엔드 서버 목록 설정
 */
@Configuration
public class ServerConfig {
    
    /**
     * Docker 컨테이너로 실행되는 4개 서버 목록
     */
    @Bean
    public List<Server> backendServers() {
        Server server1 = new Server("server-1", "localhost", 5001);
        server1.setWeight(4); // 고성능 서버
        
        Server server2 = new Server("server-2", "localhost", 5002);
        server2.setWeight(3); // 보통 성능
        
        Server server3 = new Server("server-3", "localhost", 5003);
        server3.setWeight(2); // 낮은 성능
        
        Server server4 = new Server("server-4", "localhost", 5004);
        server4.setWeight(1); // 매우 낮은 성능
        
        return Arrays.asList(server1, server2, server3, server4);
    }
}
