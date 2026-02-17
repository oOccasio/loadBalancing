package dgu.loadBalancing.service;

import dgu.loadBalancing.model.Server;
import dgu.loadBalancing.strategy.LoadBalancingStrategy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;

@Service
public class LoadBalancerService {

    private static final Logger log = LoggerFactory.getLogger(LoadBalancerService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final LoadBalancerMetrics metrics;
    private final LoadBalancingStrategy currentStrategy;
    private final ServerRegistry serverRegistry;
    private final WebClient webClient;

    public LoadBalancerService(
            LoadBalancerMetrics metrics,
            @Qualifier("leastConnections") LoadBalancingStrategy currentStrategy,
            ServerRegistry serverRegistry,
            WebClient webClient) {
        this.metrics = metrics;
        this.currentStrategy = currentStrategy;
        this.serverRegistry = serverRegistry;
        this.webClient = webClient;
    }

    public ResponseEntity<String> forwardRequest(String path, HttpServletRequest request) {

        long requestStartTime = System.currentTimeMillis();
        String algorithm = currentStrategy.getStrategyName();
        List<Server> servers = serverRegistry.getHealthyServers();
        if (servers.isEmpty()) {
            return ResponseEntity.status(503).body("No healthy servers available");
        }

        Server server = null;
        boolean success = false;

        try {
            // 알고리즘 선택 시간 측정
            long algoStart = System.nanoTime();
            server = currentStrategy.selectServer(servers, getClientInfo(request));
            long algoDuration = System.nanoTime() - algoStart;

            metrics.recordAlgorithmDuration(algorithm, algoDuration);
            metrics.incrementBackendSelection(algorithm, server.getId());
            metrics.updateActiveConnections(server.getId(), server.getCurrentConnections());

            // 실제 요청 전송
            String response = sendRequest(server, path, request);
            success = true;

            // 전략에 메트릭 업데이트
            long responseTime = System.currentTimeMillis() - requestStartTime;
            currentStrategy.updateServerMetrics(server, responseTime, success);

            return ResponseEntity.ok(response);

        } catch (WebClientResponseException e) {
            log.error("Backend returned error: {} {}", e.getStatusCode(), e.getMessage());

            // 실패해도 연결 수 감소
            if (server != null) {
                currentStrategy.updateServerMetrics(server, 0, false);
            }

            metrics.incrementErrorCount(
                    algorithm,
                    server != null ? server.getId() : "unknown",
                    "HTTP_" + e.getStatusCode().value()
            );

            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());

        } catch (Exception e) {
            log.error("Request failed: {}", e.getMessage());

            // 실패해도 연결 수 감소
            if (server != null) {
                currentStrategy.updateServerMetrics(server, 0, false);
            }

            metrics.incrementErrorCount(
                    algorithm,
                    server != null ? server.getId() : "unknown",
                    e.getClass().getSimpleName()
            );

            return ResponseEntity.internalServerError().body("Failed to forward request: " + e.getMessage());

        } finally {
            long totalResponseTime = System.currentTimeMillis() - requestStartTime;

            if (server != null) {
                metrics.incrementRequestCount(algorithm, server.getId(), success);
                metrics.recordResponseTime(algorithm, server.getId(), totalResponseTime);
                metrics.updateActiveConnections(server.getId(), server.getCurrentConnections());
            }
        }
    }

    private String sendRequest(Server server, String path, HttpServletRequest request) {
        String url = server.getUrl() + "/" + path;

        log.debug("Forwarding request to: {}", url);

        return webClient.get()
                .uri(url)
                .header("X-Forwarded-For", request.getRemoteAddr())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .block();
    }

    private String getClientInfo(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
    public String getCurrentStrategyName() {
        return currentStrategy.getStrategyName();
    }
}