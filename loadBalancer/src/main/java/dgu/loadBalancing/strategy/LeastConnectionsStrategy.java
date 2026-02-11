package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Least Connections 로드밸런싱 전략
 *
 * 동시성 처리:
 * - CAS 루프로 "최소 연결 서버 찾기 + 연결 수 증가"를 원자적으로 처리
 * - 락 없이 높은 처리량 유지
 * - Server 내부의 AtomicInteger가 개별 연산의 원자성 보장
 */
@Component("leastConnections")
public class LeastConnectionsStrategy implements LoadBalancingStrategy {

    private static final Logger log = LoggerFactory.getLogger(LeastConnectionsStrategy.class);
    private static final int MAX_RETRIES = 3;

    @Override
    public Server selectServer(List<Server> servers, String clientInfo) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("서버 목록이 비어있습니다.");
        }

        List<Server> healthyServers = servers.stream()
                .filter(Server::isHealthy)
                .toList();

        if (healthyServers.isEmpty()) {
            throw new IllegalStateException("사용 가능한 건강한 서버가 없습니다.");
        }

        // CAS 루프로 원자적 선택 + 증가
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            Server min = healthyServers.stream()
                    .min(Comparator.comparingInt(Server::getCurrentConnections)
                            .thenComparing(Server::getId))
                    .orElse(healthyServers.get(0));

            int currentConnections = min.getCurrentConnections();

            if (min.tryIncrementConnections(currentConnections)) {
                log.debug("[Least Connections] 선택: {} (연결 수: {})",
                        min.getId(), currentConnections + 1);
                return min;
            }

            log.debug("[Least Connections] CAS 실패, 재시도 {}/{}", retry + 1, MAX_RETRIES);
        }

        // MAX_RETRIES 초과 시 폴백
        Server fallback = healthyServers.stream()
                .min(Comparator.comparingInt(Server::getCurrentConnections))
                .orElse(healthyServers.get(0));

        fallback.incrementConnections();
        log.debug("[Least Connections] 폴백 선택: {}", fallback.getId());

        return fallback;
    }

    @Override
    public void updateServerMetrics(Server server, long responseTime, boolean success) {
        // AtomicInteger라 이미 원자적
        server.decrementConnections();

        if (success) {
            server.recordResponseTime(responseTime);
        }

        log.debug("[Least Connections] 연결 종료: {} (남은 연결 수: {})",
                server.getId(), server.getCurrentConnections());
    }

    @Override
    public String getStrategyName() {
        return "Least Connections";
    }

    @Override
    public String getDescription() {
        return "Least Connections - 현재 활성 연결 수가 가장 적은 서버를 선택 (CAS 기반)";
    }

    /**
     * 모든 서버의 현재 연결 상태 반환 (모니터링용)
     */
    public String getConnectionStatus(List<Server> servers) {
        StringBuilder sb = new StringBuilder("연결 상태: ");

        for (int i = 0; i < servers.size(); i++) {
            Server server = servers.get(i);
            sb.append(String.format("%s:%d", server.getId(), server.getCurrentConnections()));
            if (i < servers.size() - 1) {
                sb.append(", ");
            }
        }

        return sb.toString();
    }

    /**
     * 서버별 총 처리 요청 수 반환 (통계용)
     */
    public String getTotalRequestStats(List<Server> servers) {
        StringBuilder sb = new StringBuilder("총 요청 수: ");

        for (int i = 0; i < servers.size(); i++) {
            Server server = servers.get(i);
            sb.append(String.format("%s:%d", server.getId(), server.getTotalRequests()));
            if (i < servers.size() - 1) {
                sb.append(", ");
            }
        }

        return sb.toString();
    }

    /**
     * 가장 적은 연결 수를 가진 서버 반환 (테스트용)
     */
    public Server getServerWithLeastConnections(List<Server> servers) {
        return servers.stream()
                .filter(Server::isHealthy)
                .min(Comparator.comparingInt(Server::getCurrentConnections))
                .orElse(null);
    }

}