package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;

/**
 * Least Connections 로드밸런싱 전략
 * 현재 활성 연결 수가 가장 적은 서버를 선택하는 동적 알고리즘
 */
@Component("leastConnections")
public class LeastConnectionsStrategy implements LoadBalancingStrategy {
    
    @Override
    public Server selectServer(List<Server> servers, String clientInfo) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("서버 목록이 비어있습니다.");
        }
        
        // 건강한 서버들 중에서 현재 연결 수가 가장 적은 서버 찾기
        Server selectedServer = servers.stream()
                .filter(Server::isHealthy)
                .min(Comparator.comparingInt(Server::getCurrentConnections)
                        .thenComparing(Server::getId)) // 연결 수가 같으면 ID로 정렬 (일관성)
                .orElseThrow(() -> new IllegalStateException("사용 가능한 건강한 서버가 없습니다."));
        
        // 연결 수 증가
        selectedServer.incrementConnections();
        
        System.out.printf("[Least Connections] 선택된 서버: %s (연결 수: %d)%n", 
                selectedServer.getId(), selectedServer.getCurrentConnections());
        
        return selectedServer;
    }
    
    @Override
    public void updateServerMetrics(Server server, long responseTime, boolean success) {
        // 연결 수 감소
        server.decrementConnections();
        
        // 응답시간 기록
        if (success) {
            server.recordResponseTime(responseTime);
        }
        
        System.out.printf("[Least Connections] 연결 종료: %s (남은 연결 수: %d)%n", 
                server.getId(), server.getCurrentConnections());
    }
    
    @Override
    public String getStrategyName() {
        return "Least Connections";
    }
    
    @Override
    public String getDescription() {
        return "Least Connections - 현재 활성 연결 수가 가장 적은 서버를 선택하는 동적 알고리즘";
    }
    
    /**
     * 모든 서버의 현재 연결 상태 반환 (모니터링용)
     */
    public String getConnectionStatus(List<Server> servers) {
        StringBuilder sb = new StringBuilder();
        sb.append("연결 상태: ");
        
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
        StringBuilder sb = new StringBuilder();
        sb.append("총 요청 수: ");
        
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
    
    /**
     * 모든 서버의 연결 수를 0으로 리셋 (테스트용)
     */
    public void resetAllConnections(List<Server> servers) {
        servers.forEach(server -> {
            while (server.getCurrentConnections() > 0) {
                server.decrementConnections();
            }
        });
    }
}
