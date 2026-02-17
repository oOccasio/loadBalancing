package dgu.loadBalancing.service;

import dgu.loadBalancing.config.WebClientConfig;
import dgu.loadBalancing.model.Server;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;

/**
 * 백엔드 서버 헬스체크 서비스
 */
@Service
public class HealthCheckService {

    private final WebClient webClient;
    private final ServerRegistry serverRegistry;

    public HealthCheckService(WebClient webClient, ServerRegistry serverRegistry) {
        this.webClient = webClient;
        this.serverRegistry = serverRegistry;
    }
    /**
     * 30초마다 모든 서버 헬스체크 실행
     */
    @Scheduled(fixedRate = 5000)
    public void performHealthCheck() {
        System.out.println("[HealthCheck] 헬스체크 시작...");

        for (Server server : serverRegistry.getServers()) {
            checkServerHealth(server);
        }
    }
    
    /**
     * 개별 서버 헬스체크
     */
    public void checkServerHealth(Server server) {
        long startTime = System.currentTimeMillis();

        try {
            webClient.get()
                    .uri(server.getUrl() + "/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(WebClientConfig.HEALTH_CHECK_TIMEOUT)
                    .block();

            long responseTime = System.currentTimeMillis() - startTime;

            server.recordResponseTime(responseTime);
            serverRegistry.updateServerHealth(server.getId(), true);

            System.out.printf("[HealthCheck] %s: OK (%dms)%n",
                    server.getId(), responseTime);

        } catch (Exception e) {

            serverRegistry.updateServerHealth(server.getId(), false);

            System.err.printf("[HealthCheck] %s: FAILED - %s%n",
                    server.getId(), e.getMessage());
        }
    }
}
