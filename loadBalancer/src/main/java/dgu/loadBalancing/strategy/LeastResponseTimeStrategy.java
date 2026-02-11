package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.springframework.stereotype.Component;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Least Response Time 로드밸런싱 전략
 * 평균 응답시간이 가장 빠른 서버를 선택하는 동적 알고리즘
 */
@Component("leastResponseTime")
public class LeastResponseTimeStrategy implements LoadBalancingStrategy {
    
    // 서버별 응답시간 통계 (백업용 - Server 클래스의 데이터와 보완)
    private final Map<String, ResponseTimeStats> serverStats = new ConcurrentHashMap<>();
    
    // 초기 응답시간 (데이터 없는 서버용)
    private static final double INITIAL_RESPONSE_TIME = 1000.0; // 1초
    
    // 응답시간 가중 평균을 위한 알파값 (0.0 ~ 1.0)
    private static final double ALPHA = 0.3; // 30% 새 값, 70% 이전 평균
    
    @Override
    public Server selectServer(List<Server> servers, String clientInfo) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("서버 목록이 비어있습니다.");
        }
        
        // 건강한 서버만 필터링
        List<Server> healthyServers = servers.stream()
                .filter(Server::isHealthy)
                .toList();
        
        if (healthyServers.isEmpty()) {
            throw new IllegalStateException("사용 가능한 건강한 서버가 없습니다.");
        }
        
        // 평균 응답시간이 가장 빠른 서버 선택
        Server selectedServer = healthyServers.stream()
                .min(Comparator.comparingDouble(this::getEffectiveResponseTime)
                        .thenComparing(Server::getId)) // 응답시간이 같으면 ID로 정렬
                .orElseThrow(() -> new IllegalStateException("서버 선택에 실패했습니다."));
        
        selectedServer.incrementConnections();
        
        double responseTime = getEffectiveResponseTime(selectedServer);
        System.out.printf("[Least Response Time] 선택된 서버: %s (평균 응답시간: %.2fms)%n", 
                selectedServer.getId(), responseTime);
        
        return selectedServer;
    }
    
    @Override
    public void updateServerMetrics(Server server, long responseTime, boolean success) {
        server.decrementConnections();
        
        if (success) {
            // Server 객체에 응답시간 기록
            server.recordResponseTime(responseTime);
            
            // 추가 통계 업데이트 (가중 평균)
            updateWeightedAverage(server.getId(), responseTime);
            
            System.out.printf("[Least Response Time] 응답시간 기록: %s = %dms (평균: %.2fms)%n", 
                    server.getId(), responseTime, getEffectiveResponseTime(server));
        } else {
            // 실패한 요청에 대해서는 패널티 부여
            updateWeightedAverage(server.getId(), (long)(INITIAL_RESPONSE_TIME * 2));
            
            System.out.printf("[Least Response Time] 실패 요청 패널티: %s%n", server.getId());
        }
    }
    
    @Override
    public String getStrategyName() {
        return "Least Response Time";
    }
    
    @Override
    public String getDescription() {
        return "Least Response Time - 평균 응답시간이 가장 빠른 서버를 선택하는 동적 알고리즘";
    }
    
    @Override
    public void initialize(List<Server> servers) {
        // 모든 서버에 대해 초기 통계 설정
        for (Server server : servers) {
            if (server.isHealthy()) {
                serverStats.put(server.getId(), new ResponseTimeStats());
            }
        }
    }
    
    @Override
    public void onServerAdded(Server server) {
        if (server.isHealthy()) {
            serverStats.put(server.getId(), new ResponseTimeStats());
        }
    }
    
    @Override
    public void onServerRemoved(Server server) {
        serverStats.remove(server.getId());
    }
    
    /**
     * 서버의 효과적인 응답시간 계산
     * Server 객체의 데이터와 내부 통계를 조합
     */
    private double getEffectiveResponseTime(Server server) {
        // Server 객체에서 응답시간 가져오기
        double serverResponseTime = server.getAverageResponseTime();
        
        // 내부 통계에서 가중 평균 가져오기
        ResponseTimeStats stats = serverStats.get(server.getId());
        double weightedAverage = (stats != null) ? stats.getWeightedAverage() : INITIAL_RESPONSE_TIME;
        
        // 두 값이 모두 유효한 경우 조합, 아니면 사용 가능한 값 사용
        if (serverResponseTime != Double.MAX_VALUE && stats != null) {
            // 50:50 비율로 조합
            return (serverResponseTime + weightedAverage) / 2.0;
        } else if (serverResponseTime != Double.MAX_VALUE) {
            return serverResponseTime;
        } else if (stats != null) {
            return weightedAverage;
        } else {
            return INITIAL_RESPONSE_TIME;
        }
    }
    
    /**
     * 가중 평균 업데이트 (지수 이동 평균)
     */
    private void updateWeightedAverage(String serverId, long responseTime) {
        serverStats.computeIfAbsent(serverId, k -> new ResponseTimeStats())
                   .updateWeightedAverage(responseTime, ALPHA);
    }

    /**
     * 응답시간 통계를 관리하는 내부 클래스
     */
    public static class ResponseTimeStats {
        private double weightedAverage;
        private long requestCount;
        private long totalResponseTime;
        private boolean initialized;
        
        public ResponseTimeStats() {
            this.weightedAverage = INITIAL_RESPONSE_TIME;
            this.requestCount = 0;
            this.totalResponseTime = 0;
            this.initialized = false;
        }
        
        /**
         * 지수 이동 평균으로 가중 평균 업데이트
         */
        public synchronized void updateWeightedAverage(long newResponseTime, double alpha) {
            if (!initialized) {
                weightedAverage = newResponseTime;
                initialized = true;
            } else {
                weightedAverage = alpha * newResponseTime + (1 - alpha) * weightedAverage;
            }
            
            requestCount++;
            totalResponseTime += newResponseTime;
        }
        
        public double getWeightedAverage() {
            return weightedAverage;
        }
        
        public double getSimpleAverage() {
            return requestCount > 0 ? (double) totalResponseTime / requestCount : INITIAL_RESPONSE_TIME;
        }
        
        @Override
        public String toString() {
            return String.format("ResponseTimeStats{weighted=%.2fms, simple=%.2fms, count=%d}", 
                    weightedAverage, getSimpleAverage(), requestCount);
        }
    }
}
