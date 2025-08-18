package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round Robin 로드밸런싱 전략
 * 서버를 순차적으로 선택하는 가장 기본적인 알고리즘
 */
@Component("roundRobin")
public class RoundRobinStrategy implements LoadBalancingStrategy {
    
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    
    @Override
    public Server selectServer(List<Server> servers, String clientInfo) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("서버 목록이 비어있습니다.");
        }
        
        // 현재 인덱스를 원자적으로 증가시키고 서버 선택
        int index = currentIndex.getAndIncrement() % servers.size();
        Server selectedServer = servers.get(index);
        
        // 연결 수 증가
        selectedServer.incrementConnections();
        
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
    }
    
    @Override
    public String getStrategyName() {
        return "Round Robin";
    }
    
    @Override
    public String getDescription() {
        return "Round Robin - 서버를 순차적으로 선택하는 기본 알고리즘";
    }
    
    /**
     * 현재 인덱스를 리셋 (테스트용)
     */
    public void reset() {
        currentIndex.set(0);
    }
    
    /**
     * 현재 인덱스 반환 (모니터링용)
     */
    public int getCurrentIndex() {
        return currentIndex.get();
    }
}
