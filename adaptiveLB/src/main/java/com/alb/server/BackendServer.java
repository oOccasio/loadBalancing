package com.alb.server;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BackendServer {

    private final String id;
    private final String url;
    private volatile boolean healthy = true;
    private volatile int weight;

    // Simulation config (artificial latency/error for testing without real backends)
    private volatile long simulatedLatencyMs = 0;
    private volatile double errorRate = 0.0;

    // Connection tracking
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    // Recent response times (last 50) for p95 calculation
    private final Queue<Long> recentResponseTimes = new LinkedList<>();
    private static final int MAX_RECENT = 50;
    private final Object responseTimeLock = new Object();

    public BackendServer(String id, String url, int weight) {
        this.id = id;
        this.url = url;
        this.weight = weight;
    }

    public BackendServer(String id, String url, int weight, long simulatedLatencyMs, double errorRate) {
        this(id, url, weight);
        this.simulatedLatencyMs = simulatedLatencyMs;
        this.errorRate = errorRate;
    }

    public void incrementConnections() {
        activeConnections.incrementAndGet();
    }

    public void decrementConnections() {
        activeConnections.updateAndGet(v -> Math.max(0, v - 1));
    }

    public boolean tryIncrementConnections(int expected) {
        return activeConnections.compareAndSet(expected, expected + 1);
    }

    public void recordResponse(long latencyMs, boolean success) {
        totalRequests.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        if (!success) {
            errorCount.incrementAndGet();
        }
        synchronized (responseTimeLock) {
            recentResponseTimes.add(latencyMs);
            if (recentResponseTimes.size() > MAX_RECENT) {
                recentResponseTimes.poll();
            }
        }
    }

    public double getAverageLatencyMs() {
        long requests = totalRequests.get();
        if (requests == 0) return 0.0;
        return (double) totalLatencyMs.get() / requests;
    }

    public double getErrorRate() {
        long requests = totalRequests.get();
        if (requests == 0) return 0.0;
        return (double) errorCount.get() / requests;
    }

    public long[] getRecentResponseTimes() {
        synchronized (responseTimeLock) {
            return recentResponseTimes.stream().mapToLong(Long::longValue).toArray();
        }
    }

    public String getId() { return id; }
    public String getUrl() { return url; }
    public boolean isHealthy() { return healthy; }
    public void setHealthy(boolean healthy) { this.healthy = healthy; }
    public int getWeight() { return weight; }
    public void setWeight(int weight) { this.weight = weight; }
    public int getActiveConnections() { return activeConnections.get(); }
    public long getTotalRequests() { return totalRequests.get(); }
    public long getErrorCount() { return errorCount.get(); }
    public long getSimulatedLatencyMs() { return simulatedLatencyMs; }
    public void setSimulatedLatencyMs(long simulatedLatencyMs) { this.simulatedLatencyMs = simulatedLatencyMs; }
    public double getConfiguredErrorRate() { return errorRate; }
    public void setErrorRate(double errorRate) { this.errorRate = errorRate; }

    @Override
    public String toString() {
        return String.format("BackendServer{id='%s', url='%s', healthy=%b, connections=%d, weight=%d}",
                id, url, healthy, activeConnections.get(), weight);
    }
}
