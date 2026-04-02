package com.alb.metrics;

import com.alb.algorithm.AlgorithmType;
import com.alb.analyzer.TrafficState;
import com.alb.engine.DecisionEngine;
import com.alb.server.BackendServer;
import com.alb.server.ServerPool;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registers custom ALB metrics into Micrometer so they are exposed via
 * /actuator/prometheus and can be scraped by Prometheus + visualised in Grafana.
 *
 * Metrics published:
 *   alb_rps                          – requests per second (current window)
 *   alb_avg_latency_ms               – average latency ms
 *   alb_p95_latency_ms               – p95 latency ms
 *   alb_error_rate                   – error rate (0.0–1.0)
 *   alb_server_connections{server}   – active connections per backend server
 *   alb_algorithm{algorithm}         – 1 if algorithm is active, 0 otherwise
 *   alb_traffic_state{state}         – 1 if state is current, 0 otherwise
 *   alb_algorithm_switches_total     – cumulative algorithm switch count
 */
@Component
public class PrometheusMetricsPublisher {

    private final MeterRegistry registry;
    private final MetricsCollector metricsCollector;
    private final ServerPool serverPool;
    private final DecisionEngine decisionEngine;

    private final AtomicInteger switchCount = new AtomicInteger(0);
    private volatile AlgorithmType lastAlgorithm;

    public PrometheusMetricsPublisher(MeterRegistry registry,
                                      MetricsCollector metricsCollector,
                                      ServerPool serverPool,
                                      DecisionEngine decisionEngine) {
        this.registry = registry;
        this.metricsCollector = metricsCollector;
        this.serverPool = serverPool;
        this.decisionEngine = decisionEngine;
    }

    @PostConstruct
    public void registerMetrics() {
        // ── Sliding-window LB metrics ─────────────────────────────────────────

        Gauge.builder("alb_rps", this, p -> latestSnapshot() != null ? latestSnapshot().rps() : 0)
                .description("Requests per second in the current metrics window")
                .register(registry);

        Gauge.builder("alb_avg_latency_ms", this, p -> latestSnapshot() != null ? latestSnapshot().avgLatencyMs() : 0)
                .description("Average request latency (ms) in the current window")
                .register(registry);

        Gauge.builder("alb_p95_latency_ms", this, p -> latestSnapshot() != null ? latestSnapshot().p95LatencyMs() : 0)
                .description("p95 request latency (ms) in the current window")
                .register(registry);

        Gauge.builder("alb_error_rate", this, p -> latestSnapshot() != null ? latestSnapshot().errorRate() : 0)
                .description("Error rate (0.0–1.0) in the current window")
                .register(registry);

        // ── Per-server connection gauges ──────────────────────────────────────

        for (BackendServer server : serverPool.getServers()) {
            Gauge.builder("alb_server_connections", server, BackendServer::getActiveConnections)
                    .description("Active connections per backend server")
                    .tag("server", server.getId())
                    .register(registry);
        }

        // ── Current algorithm (one gauge per algorithm, value 1 = active) ────

        for (AlgorithmType algo : AlgorithmType.values()) {
            Gauge.builder("alb_algorithm", algo,
                            a -> a == serverPool.getCurrentAlgorithmType() ? 1 : 0)
                    .description("1 if this algorithm is currently active")
                    .tag("algorithm", algo.name())
                    .register(registry);
        }

        // ── Current traffic state (one gauge per state, value 1 = active) ────

        for (TrafficState state : TrafficState.values()) {
            Gauge.builder("alb_traffic_state", state,
                            s -> s == decisionEngine.getCurrentState() ? 1 : 0)
                    .description("1 if this traffic state is currently detected")
                    .tag("state", state.name())
                    .register(registry);
        }

        // ── Algorithm switch counter ──────────────────────────────────────────

        Gauge.builder("alb_algorithm_switches_total", switchCount, AtomicInteger::get)
                .description("Cumulative number of algorithm switches")
                .register(registry);
    }

    @Scheduled(fixedDelay = 1000)
    public void trackAlgorithmSwitch() {
        AlgorithmType current = serverPool.getCurrentAlgorithmType();
        if (lastAlgorithm != null && current != lastAlgorithm) {
            switchCount.incrementAndGet();
        }
        lastAlgorithm = current;
    }

    private MetricsSnapshot latestSnapshot() {
        return metricsCollector.getLatestSnapshot();
    }
}
