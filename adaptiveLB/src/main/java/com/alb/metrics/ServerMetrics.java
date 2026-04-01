package com.alb.metrics;

public record ServerMetrics(
        double avgLatencyMs,
        double errorRate,
        int activeConnections,
        long totalRequests
) {}
