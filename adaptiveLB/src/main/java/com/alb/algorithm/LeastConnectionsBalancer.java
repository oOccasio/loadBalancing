package com.alb.algorithm;

import com.alb.server.BackendServer;

import java.util.Comparator;
import java.util.List;

public class LeastConnectionsBalancer implements LoadBalancer {

    private static final int MAX_RETRIES = 3;

    @Override
    public BackendServer select(List<BackendServer> servers, String clientInfo) {
        List<BackendServer> healthy = servers.stream().filter(BackendServer::isHealthy).toList();
        if (healthy.isEmpty()) return null;

        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            BackendServer min = healthy.stream()
                    .min(Comparator.comparingInt(BackendServer::getActiveConnections))
                    .orElse(healthy.get(0));

            int current = min.getActiveConnections();
            if (min.tryIncrementConnections(current)) {
                return min;
            }
        }

        // Fallback: direct increment after retries
        BackendServer fallback = healthy.stream()
                .min(Comparator.comparingInt(BackendServer::getActiveConnections))
                .orElse(healthy.get(0));
        fallback.incrementConnections();
        return fallback;
    }

    @Override
    public void onRequestComplete(BackendServer server, long latencyMs, boolean success) {
        server.decrementConnections();
    }

    @Override
    public AlgorithmType getType() {
        return AlgorithmType.LEAST_CONNECTIONS;
    }
}
