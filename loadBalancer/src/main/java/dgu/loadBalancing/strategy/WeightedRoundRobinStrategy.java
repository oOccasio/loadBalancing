package dgu.loadBalancing.strategy;

import dgu.loadBalancing.model.Server;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Weighted Round Robin 로드밸런싱 전략
 * 서버별 가중치에 따라 요청을 분산하는 알고리즘
 */
@Component("weightedRoundRobin")
public class WeightedRoundRobinStrategy implements LoadBalancingStrategy {
    
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private volatile List<Server> weightedServerList = new ArrayList<>();
    private final Object listLock = new Object();
    
    @Override
    public void initialize(List<Server> servers) {
        synchronized (listLock) {
            buildWeightedServerList(servers);
        }
    }
    
    @Override
    public Server selectServer(List<Server> servers, String clientInfo) {
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("서버 목록이 비어있습니다.");
        }
        
        // 가중치 리스트가 비어있거나 서버 목록이 변경된 경우 재구성
        synchronized (listLock) {
            if (weightedServerList.isEmpty() || needsRebuild(servers)) {
                buildWeightedServerList(servers);
            }
        }
        
        // 가중치 기반 서버 선택
        if (weightedServerList.isEmpty()) {
            throw new IllegalStateException("가중치 서버 리스트가 비어있습니다.");
        }

        List<Server> snapshot = weightedServerList;
        int index = currentIndex.getAndIncrement() % snapshot.size();
        Server server = snapshot.get(index);
        // 연결 수 증가
        server.incrementConnections();
        
        return server;
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
        return "Weighted Round Robin";
    }
    
    @Override
    public String getDescription() {
        return "Weighted Round Robin - 서버별 가중치에 따라 요청을 분산하는 알고리즘";
    }
    
    /**
     * 가중치 기반 서버 리스트 구성
     * 가중치만큼 서버를 리스트에 반복 추가
     */
    private void buildWeightedServerList(List<Server> servers) {
        weightedServerList = new ArrayList<>();
        
        for (Server server : servers) {
            if (server.isHealthy()) {
                int weight = Math.max(1, server.getWeight()); // 최소 가중치 1
                for (int i = 0; i < weight; i++) {
                    weightedServerList.add(server);
                }
            }
        }
        
        // 인덱스 리셋
        currentIndex.set(0);
        
        System.out.println("가중치 서버 리스트 재구성 완료: " + getWeightDistribution(servers));
    }
    
    /**
     * 서버 목록이 변경되어 재구성이 필요한지 확인
     */
    private boolean needsRebuild(List<Server> servers) {
        if (weightedServerList.isEmpty()) {
            return true;
        }
        
        // 현재 가중치 리스트에 있는 서버들과 비교
        List<Server> uniqueServersInList = weightedServerList.stream()
                .distinct()
                .toList();
        
        List<Server> healthyServers = servers.stream()
                .filter(Server::isHealthy)
                .toList();
        
        return !uniqueServersInList.equals(healthyServers);
    }
    
    /**
     * 가중치 분배 현황 반환 (모니터링용)
     */
    public String getWeightDistribution(List<Server> servers) {
        StringBuilder sb = new StringBuilder();
        int totalWeight = servers.stream()
                .filter(Server::isHealthy)
                .mapToInt(Server::getWeight)
                .sum();
        
        sb.append("[");
        for (int i = 0; i < servers.size(); i++) {
            Server server = servers.get(i);
            if (server.isHealthy()) {
                double percentage = (double) server.getWeight() / totalWeight * 100;
                sb.append(String.format("%s:%.1f%%", server.getId(), percentage));
                if (i < servers.size() - 1) {
                    sb.append(", ");
                }
            }
        }
        sb.append("]");
        
        return sb.toString();
    }
    
    /**
     * 현재 가중치 서버 리스트 크기 반환 (테스트용)
     */
    public int getWeightedListSize() {
        return weightedServerList.size();
    }
    
    /**
     * 인덱스 리셋 (테스트용)
     */
    public void reset() {
        currentIndex.set(0);
    }
}
