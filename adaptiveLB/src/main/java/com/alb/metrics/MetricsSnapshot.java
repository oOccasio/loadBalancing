package com.alb.metrics;

import java.util.Map;

public record MetricsSnapshot(
        long timestamp,
        double rps,
        double avgLatencyMs,
        double p95LatencyMs,
        double errorRate,
        Map<String, ServerMetrics> serverMetrics
) {}
