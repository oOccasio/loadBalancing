package com.alb.algorithm;

import com.alb.server.BackendServer;

import java.util.Comparator;
import java.util.List;

/**
 * Selects the server with the lowest ratio of active connections to weight.
 * Lower ratio = more capacity available relative to server's weight.
 */
public class WeightedLeastConnectionsBalancer implements LoadBalancer {

    private static final int MAX_RETRIES = 3;

    @Override
    public BackendServer select(List<BackendServer> servers, String clientInfo) {
        List<BackendServer> healthy = servers.stream().filter(BackendServer::isHealthy).toList();
        if (healthy.isEmpty()) return null;

        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            BackendServer best = healthy.stream()
                    .min(Comparator.comparingDouble(this::connectionRatio))
                    .orElse(healthy.get(0));

            int current = best.getActiveConnections();
            if (best.tryIncrementConnections(current)) {
                return best;
            }
        }

        BackendServer fallback = healthy.stream()
                .min(Comparator.comparingDouble(this::connectionRatio))
                .orElse(healthy.get(0));
        fallback.incrementConnections();
        return fallback;
    }

    private double connectionRatio(BackendServer server) {
        int weight = Math.max(1, server.getWeight());
        return (double) server.getActiveConnections() / weight;
    }

    @Override
    public void onRequestComplete(BackendServer server, long latencyMs, boolean success) {
        server.decrementConnections();
    }

    @Override
    public AlgorithmType getType() {
        return AlgorithmType.WEIGHTED_LEAST_CONNECTIONS;
    }
}
