package com.alb.analyzer;

import com.alb.metrics.MetricsSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Computes time-series derivatives (rate of change) from a list of metric snapshots.
 *
 * Using derivatives rather than absolute thresholds makes the analyzer
 * independent of traffic scale — a 100% RPS jump is a spike whether
 * baseline RPS is 10 or 1000.
 */
@Component
public class DerivativeCalculator {

    /**
     * RPS change rate between the last two snapshots.
     * Formula: (current - previous) / previous
     * Returns 0 if previous RPS is 0 or there are fewer than 2 snapshots.
     */
    public double rpsChangeRate(List<MetricsSnapshot> snapshots) {
        if (snapshots.size() < 2) return 0.0;
        double prev = snapshots.get(snapshots.size() - 2).rps();
        double curr = snapshots.get(snapshots.size() - 1).rps();
        if (prev == 0.0) return curr > 0 ? 1.0 : 0.0;
        return (curr - prev) / prev;
    }

    /**
     * Latency trend: slope of avg latency over the last N snapshots (ms per window).
     * Computed via simple linear regression on window index vs latency.
     * A positive slope means latency is trending up.
     */
    public double latencySlope(List<MetricsSnapshot> snapshots, int windowCount) {
        if (snapshots.size() < 2) return 0.0;
        int n = Math.min(windowCount, snapshots.size());
        List<MetricsSnapshot> recent = snapshots.subList(snapshots.size() - n, snapshots.size());

        // Simple linear regression: slope = (n*Σxy - Σx*Σy) / (n*Σx² - (Σx)²)
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < recent.size(); i++) {
            double x = i;
            double y = recent.get(i).avgLatencyMs();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        double denom = n * sumX2 - sumX * sumX;
        if (denom == 0) return 0.0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    /**
     * Returns the most recent RPS value, or 0 if no snapshots available.
     */
    public double currentRps(List<MetricsSnapshot> snapshots) {
        if (snapshots.isEmpty()) return 0.0;
        return snapshots.get(snapshots.size() - 1).rps();
    }

    /**
     * Returns the most recent p95 latency, or 0 if no snapshots available.
     */
    public double currentP95Latency(List<MetricsSnapshot> snapshots) {
        if (snapshots.isEmpty()) return 0.0;
        return snapshots.get(snapshots.size() - 1).p95LatencyMs();
    }
}
