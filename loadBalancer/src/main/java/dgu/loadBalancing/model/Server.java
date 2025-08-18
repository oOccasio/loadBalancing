package dgu.loadBalancing.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedList;
import java.util.Queue;

/**
 * 로드밸런싱 대상 서버를 나타내는 모델 클래스
 */
public class Server {
    private final String id;
    private final String host;
    private final int port;
    private final String url;
    private boolean healthy;
    private int weight;
    
    // 실시간 메트릭
    private final AtomicInteger currentConnections;
    private final AtomicLong totalRequests;
    private final Queue<Long> recentResponseTimes; // 최근 10개 응답시간
    private final Object responseTimeLock = new Object();
    
    public Server(String id, String host, int port) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.url = "http://" + host + ":" + port;
        this.healthy = true;
        this.weight = 1; // 기본 가중치
        this.currentConnections = new AtomicInteger(0);
        this.totalRequests = new AtomicLong(0);
        this.recentResponseTimes = new LinkedList<>();
    }
    
    // 연결 수 관리
    public void incrementConnections() {
        currentConnections.incrementAndGet();
        totalRequests.incrementAndGet();
    }
    
    public void decrementConnections() {
        currentConnections.decrementAndGet();
    }
    
    // 응답시간 기록
    public void recordResponseTime(long responseTime) {
        synchronized (responseTimeLock) {
            recentResponseTimes.offer(responseTime);
            // 최근 10개만 유지
            if (recentResponseTimes.size() > 10) {
                recentResponseTimes.poll();
            }
        }
    }
    
    // 평균 응답시간 계산
    public double getAverageResponseTime() {
        synchronized (responseTimeLock) {
            if (recentResponseTimes.isEmpty()) {
                return Double.MAX_VALUE; // 데이터 없으면 최악으로 간주
            }
            return recentResponseTimes.stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(Double.MAX_VALUE);
        }
    }
    
    // Getters
    public String getId() { return id; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getUrl() { return url; }
    public boolean isHealthy() { return healthy; }
    public int getWeight() { return weight; }
    public int getCurrentConnections() { return currentConnections.get(); }
    public long getTotalRequests() { return totalRequests.get(); }
    
    // Setters
    public void setHealthy(boolean healthy) { this.healthy = healthy; }
    public void setWeight(int weight) { this.weight = weight; }
    
    @Override
    public String toString() {
        return String.format("Server{id='%s', url='%s', healthy=%s, connections=%d, avgResponseTime=%.2fms}", 
                id, url, healthy, getCurrentConnections(), getAverageResponseTime());
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Server server = (Server) obj;
        return id.equals(server.id);
    }
    
    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
