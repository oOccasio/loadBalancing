package com.alb.metrics;

import com.alb.server.BackendServer;
import com.alb.server.ServerPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sliding-window metrics collector.
 *
 * Raw request events are accumulated in {@code currentWindow}. Every
 * {@code windowSizeSeconds}, a {@link MetricsSnapshot} is produced from
 * the current window and appended to {@code snapshots} (capped at
 * {@code maxWindows}).
 */
@Component
public class MetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(MetricsCollector.class);

    @Value("${alb.metrics.window-size-seconds:10}")
    private int windowSizeSeconds;

    @Value("${alb.metrics.max-windows:10}")
    private int maxWindows;

    private final ServerPool serverPool;

    // Raw events in the current window
    private final ConcurrentLinkedQueue<RequestEvent> currentWindow = new ConcurrentLinkedQueue<>();

    // Completed snapshots (oldest first)
    private final LinkedList<MetricsSnapshot> snapshots = new LinkedList<>();

    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());

    public MetricsCollector(ServerPool serverPool) {
        this.serverPool = serverPool;
    }

    /**
     * Record a completed request event. Called from RequestProxy after each request.
     */
    public void record(String serverId, long latencyMs, boolean success) {
        currentWindow.add(new RequestEvent(System.currentTimeMillis(), serverId, latencyMs, success));
    }

    /**
     * Flush current window into a snapshot. Runs on a fixed schedule.
     */
    @Scheduled(fixedDelayString = "${alb.metrics.window-size-seconds:10}000")
    public void flushWindow() {
        List<RequestEvent> events = drainCurrentWindow();
        MetricsSnapshot snapshot = buildSnapshot(events);

        synchronized (snapshots) {
            snapshots.addLast(snapshot);
            while (snapshots.size() > maxWindows) {
                snapshots.pollFirst();
            }
        }

        log.debug("Metrics snapshot: rps={} avgLatency={}ms p95={}ms errorRate={}",
                String.format("%.2f", snapshot.rps()),
                String.format("%.2f", snapshot.avgLatencyMs()),
                String.format("%.2f", snapshot.p95LatencyMs()),
                String.format("%.4f", snapshot.errorRate()));
    }

    private List<RequestEvent> drainCurrentWindow() {
        List<RequestEvent> events = new ArrayList<>();
        RequestEvent event;
        while ((event = currentWindow.poll()) != null) {
            events.add(event);
        }
        long now = System.currentTimeMillis();
        windowStartTime.set(now);
        return events;
    }

    private MetricsSnapshot buildSnapshot(List<RequestEvent> events) {
        long now = System.currentTimeMillis();

        if (events.isEmpty()) {
            Map<String, ServerMetrics> serverMetrics = buildServerMetrics();
            return new MetricsSnapshot(now, 0, 0, 0, 0, serverMetrics);
        }

        double rps = (double) events.size() / windowSizeSeconds;
        double avgLatency = events.stream().mapToLong(RequestEvent::latencyMs).average().orElse(0);
        double p95Latency = computeP95(events.stream().mapToLong(RequestEvent::latencyMs).sorted().toArray());
        long errors = events.stream().filter(e -> !e.success()).count();
        double errorRate = (double) errors / events.size();

        Map<String, ServerMetrics> serverMetrics = buildServerMetrics();

        return new MetricsSnapshot(now, rps, avgLatency, p95Latency, errorRate, serverMetrics);
    }

    private double computeP95(long[] sortedLatencies) {
        if (sortedLatencies.length == 0) return 0;
        int index = (int) Math.ceil(0.95 * sortedLatencies.length) - 1;
        return sortedLatencies[Math.min(index, sortedLatencies.length - 1)];
    }

    private Map<String, ServerMetrics> buildServerMetrics() {
        Map<String, ServerMetrics> map = new LinkedHashMap<>();
        for (BackendServer server : serverPool.getServers()) {
            map.put(server.getId(), new ServerMetrics(
                    server.getAverageLatencyMs(),
                    server.getErrorRate(),
                    server.getActiveConnections(),
                    server.getTotalRequests()
            ));
        }
        return map;
    }

    /** Returns all completed snapshots (oldest first). */
    public List<MetricsSnapshot> getSnapshots() {
        synchronized (snapshots) {
            return new ArrayList<>(snapshots);
        }
    }

    /** Returns the most recent completed snapshot, or null if none yet. */
    public MetricsSnapshot getLatestSnapshot() {
        synchronized (snapshots) {
            return snapshots.isEmpty() ? null : snapshots.getLast();
        }
    }

    /** Returns the last N snapshots (most recent last). */
    public List<MetricsSnapshot> getRecentSnapshots(int n) {
        synchronized (snapshots) {
            int size = snapshots.size();
            return new ArrayList<>(snapshots.subList(Math.max(0, size - n), size));
        }
    }

    // --- inner record for raw events ---

    private record RequestEvent(long timestampMs, String serverId, long latencyMs, boolean success) {}
}
