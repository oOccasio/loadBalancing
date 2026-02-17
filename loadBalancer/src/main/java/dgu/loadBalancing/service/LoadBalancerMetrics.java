package dgu.loadBalancing.service;

import dgu.loadBalancing.model.Server;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class LoadBalancerMetrics {

    private final MeterRegistry registry;
    private final Map<String, Integer> activeConnections = new ConcurrentHashMap<>();

    public LoadBalancerMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 1. 요청 수 카운터
     */
    public void incrementRequestCount(String algorithm, String serverId, boolean success) {
        Counter.builder("loadbalancer_requests_total")
                .description("Total number of requests processed")
                .tag("server", serverId)
                .tag("status", success ? "success" : "error")
                .register(registry)
                .increment();
    }

    /**
     * 2. 전체 응답 시간 (백엔드 포함)
     */
    public void recordResponseTime(String algorithm, String serverId, long responseTimeMs) {
        Timer.builder("loadbalancer_response_time")
                .description("Total response time including backend")
                .tag("server", serverId)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(responseTimeMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 3. 알고리즘 선택 시간 (LB 자체 오버헤드)
     */
    public void recordAlgorithmDuration(String algorithm, long durationNanos) {
        Timer.builder("loadbalancer_algorithm_duration_seconds")
                .description("Time spent selecting a server")
                .tag("algorithm", algorithm)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 4. 활성 연결 수
     */
    public void updateActiveConnections(String serverId, int connections) {
        activeConnections.put(serverId, connections);

        Gauge.builder("loadbalancer_active_connections", activeConnections,
                        map -> map.getOrDefault(serverId, 0).doubleValue())
                .description("Current active connections per server")
                .tag("server", serverId)
                .register(registry);
    }

    /**
     * 5. 에러 카운터
     */
    public void incrementErrorCount(String algorithm, String serverId, String errorType) {
        Counter.builder("loadbalancer_errors_total")
                .description("Total number of errors")
                .tag("server", serverId)
                .tag("error_type", errorType)
                .register(registry)
                .increment();
    }

    /**
     * 6. 서버 헬스 상태
     */
    public void registerServerHealth(Server server) {
        Gauge.builder("loadbalancer_server_health",
                        server,
                        s -> s.isHealthy() ? 1.0 : 0.0)
                .description("Server health status (1=healthy, 0=unhealthy)")
                .tag("server", server.getId())
                .register(registry);
    }

    /**
     * 7. 백엔드 선택 카운터 (분산 균등성 측정용)
     */
    public void incrementBackendSelection(String algorithm, String serverId) {
        Counter.builder("loadbalancer_backend_selection_total")
                .description("Number of times each backend was selected")
                .tag("server", serverId)
                .register(registry)
                .increment();
    }

    /**
     * 8. 재시도 카운터
     */
    public void incrementRetryCount(String algorithm, String serverId) {
        Counter.builder("loadbalancer_retries_total")
                .description("Total number of retries")
                .tag("server", serverId)
                .register(registry)
                .increment();
    }
}