package com.alb.analyzer;

import com.alb.metrics.MetricsSnapshot;
import com.alb.metrics.ServerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Analyzes recent metric snapshots and classifies the current traffic state.
 *
 * Classification priority (highest to lowest):
 *  1. OVERLOADED_NODE — a server is clearly struggling (checked first)
 *  2. SPIKE           — explosive RPS growth
 *  3. GRADUAL_INCREASE — steady RPS growth
 *  4. HIGH_STABLE     — high RPS, stable metrics
 *  5. LOW_TRAFFIC     — baseline fallback
 */
@Component
public class PatternAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(PatternAnalyzer.class);

    private final DerivativeCalculator derivativeCalculator;
    private final AnalyzerConfig config;

    public PatternAnalyzer(DerivativeCalculator derivativeCalculator, AnalyzerConfig config) {
        this.derivativeCalculator = derivativeCalculator;
        this.config = config;
    }

    /**
     * Analyze the snapshot history and return a {@link StateAnalysis} containing
     * the detected state, a confidence score, and a human-readable reason string.
     */
    public StateAnalysis analyze(List<MetricsSnapshot> snapshots) {
        if (snapshots == null || snapshots.size() < config.getMinSnapshotsRequired()) {
            return new StateAnalysis(TrafficState.LOW_TRAFFIC, 0.5,
                    "Insufficient data (need " + config.getMinSnapshotsRequired() + " snapshots)");
        }

        double currentRps = derivativeCalculator.currentRps(snapshots);
        double rpsDerivative = derivativeCalculator.rpsChangeRate(snapshots);
        double latencySlope = derivativeCalculator.latencySlope(snapshots, config.getLatencyTrendWindows());
        double p95 = derivativeCalculator.currentP95Latency(snapshots);
        MetricsSnapshot latest = snapshots.get(snapshots.size() - 1);

        // 1. OVERLOADED_NODE — any server with abnormal error rate or latency
        String overloadedServer = findOverloadedServer(latest);
        if (overloadedServer != null) {
            double confidence = 0.9;
            String reason = String.format(
                    "Server '%s' is overloaded: high error rate or latency exceeds %.0fms threshold.",
                    overloadedServer, config.getServerLatencyThreshold());
            log.debug("[PatternAnalyzer] OVERLOADED_NODE: {}", reason);
            return new StateAnalysis(TrafficState.OVERLOADED_NODE, confidence, reason);
        }

        // 2. LOW_TRAFFIC — RPS below threshold
        if (currentRps < config.getLowRpsThreshold()) {
            String reason = String.format(
                    "Current RPS=%.2f is below low-traffic threshold (%.1f).",
                    currentRps, config.getLowRpsThreshold());
            log.debug("[PatternAnalyzer] LOW_TRAFFIC: {}", reason);
            return new StateAnalysis(TrafficState.LOW_TRAFFIC, 0.8, reason);
        }

        // 3. SPIKE — explosive RPS increase
        if (rpsDerivative >= config.getSpikeRpsDerivativeThreshold()) {
            double confidence = Math.min(0.99, 0.7 + rpsDerivative * 0.15);
            String reason = String.format(
                    "RPS derivative=%.2f (≥%.1f threshold) → explosive growth detected. "
                            + "Current RPS=%.2f, p95=%.0fms.",
                    rpsDerivative, config.getSpikeRpsDerivativeThreshold(), currentRps, p95);
            log.debug("[PatternAnalyzer] SPIKE: {}", reason);
            return new StateAnalysis(TrafficState.SPIKE, confidence, reason);
        }

        // 4. GRADUAL_INCREASE — steady RPS growth
        if (rpsDerivative >= config.getGradualRpsDerivativeThreshold()) {
            double confidence = 0.6 + rpsDerivative * 0.3;
            String reason = String.format(
                    "RPS derivative=%.2f (≥%.1f threshold) → gradual growth. "
                            + "Latency slope=%.1fms/window over last %d windows.",
                    rpsDerivative, config.getGradualRpsDerivativeThreshold(),
                    latencySlope, config.getLatencyTrendWindows());
            log.debug("[PatternAnalyzer] GRADUAL_INCREASE: {}", reason);
            return new StateAnalysis(TrafficState.GRADUAL_INCREASE, confidence, reason);
        }

        // 5. HIGH_STABLE — high RPS, metrics stable
        String reason = String.format(
                "RPS=%.2f is high and stable (derivative=%.2f). "
                        + "Latency slope=%.1fms/window, p95=%.0fms.",
                currentRps, rpsDerivative, latencySlope, p95);
        log.debug("[PatternAnalyzer] HIGH_STABLE: {}", reason);
        return new StateAnalysis(TrafficState.HIGH_STABLE, 0.75, reason);
    }

    private String findOverloadedServer(MetricsSnapshot snapshot) {
        for (Map.Entry<String, ServerMetrics> entry : snapshot.serverMetrics().entrySet()) {
            ServerMetrics m = entry.getValue();
            if (m.errorRate() > config.getServerErrorRateThreshold()
                    || m.avgLatencyMs() > config.getServerLatencyThreshold()) {
                return entry.getKey();
            }
        }
        return null;
    }

    public record StateAnalysis(TrafficState state, double confidence, String reason) {}
}
