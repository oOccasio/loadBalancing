package dgu.loadBalancing.model;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedList;
import java.util.Queue;

@Getter
public class Server {
    private final String id;
    private final String url;

    @Setter
    private volatile boolean healthy;
    @Setter
    private int weight;

    private final AtomicInteger currentConnections;
    private final AtomicLong totalRequests;
    private final Queue<Long> recentResponseTimes;
    private final Object responseTimeLock = new Object();

    public Server(String id, String url) {
        this(id, url, 1);
    }

    public Server(String id, String url, int weight) {
        this.id = id;
        this.url = url;
        this.healthy = true;
        this.weight = weight;
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
        currentConnections.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    public boolean tryIncrementConnections(int expected) {
        boolean success = currentConnections.compareAndSet(expected, expected + 1);
        if (success) {
            totalRequests.incrementAndGet();
        }
        return success;
    }

    // 응답시간 기록
    public void recordResponseTime(long responseTime) {
        synchronized (responseTimeLock) {
            recentResponseTimes.offer(responseTime);
            if (recentResponseTimes.size() > 10) {
                recentResponseTimes.poll();
            }
        }
    }

    // 평균 응답시간 계산
    public double getAverageResponseTime() {
        synchronized (responseTimeLock) {
            if (recentResponseTimes.isEmpty()) {
                return Double.MAX_VALUE;
            }
            return recentResponseTimes.stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(Double.MAX_VALUE);
        }
    }

    public int getCurrentConnections() {
        return currentConnections.get();
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    @Override
    public String toString() {
        return String.format("Server{id='%s', url='%s', healthy=%s, weight=%d, connections=%d}",
                id, url, healthy, weight, getCurrentConnections());
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