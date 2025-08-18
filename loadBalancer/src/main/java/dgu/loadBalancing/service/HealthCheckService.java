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
    
    @Autowired
    private WebClient webClient;
    
    @Autowired
    private List<Server> backendServers;
    
    /**
     * 30초마다 모든 서버 헬스체크 실행
     */
    @Scheduled(fixedRate = 30000)
    public void performHealthCheck() {
        System.out.println("[HealthCheck] 헬스체크 시작...");
        
        for (Server server : backendServers) {
            checkServerHealth(server);
        }
    }
    
    /**
     * 개별 서버 헬스체크
     */
    public void checkServerHealth(Server server) {
        try {
            long startTime = System.currentTimeMillis();
            
            webClient.get()
                    .uri(server.getUrl() + "/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(WebClientConfig.HEALTH_CHECK_TIMEOUT)
                    .subscribe(
                        response -> {
                            long responseTime = System.currentTimeMillis() - startTime;
                            server.setHealthy(true);
                            server.recordResponseTime(responseTime);
                            System.out.printf("[HealthCheck] %s: OK (%dms)%n", 
                                    server.getId(), responseTime);
                        },
                        error -> {
                            server.setHealthy(false);
                            System.err.printf("[HealthCheck] %s: FAILED - %s%n", 
                                    server.getId(), error.getMessage());
                        }
                    );
                    
        } catch (Exception e) {
            server.setHealthy(false);
            System.err.printf("[HealthCheck] %s: EXCEPTION - %s%n", 
                    server.getId(), e.getMessage());
        }
    }
    
    /**
     * 건강한 서버 목록 반환
     */
    public List<Server> getHealthyServers() {
        return backendServers.stream()
                .filter(Server::isHealthy)
                .toList();
    }
    
    /**
     * 모든 서버 상태 요약
     */
    public String getHealthSummary() {
        long healthyCount = backendServers.stream()
                .filter(Server::isHealthy)
                .count();
        
        return String.format("서버 상태: %d/%d 건강함", 
                healthyCount, backendServers.size());
    }
}
