package com.alb.algorithm;

import com.alb.server.BackendServer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class RoundRobinBalancer implements LoadBalancer {

    private final AtomicInteger index = new AtomicInteger(0);

    @Override
    public BackendServer select(List<BackendServer> servers, String clientInfo) {
        List<BackendServer> healthy = servers.stream().filter(BackendServer::isHealthy).toList();
        if (healthy.isEmpty()) return null;
        return healthy.get(Math.abs(index.getAndIncrement() % healthy.size()));
    }

    @Override
    public AlgorithmType getType() {
        return AlgorithmType.ROUND_ROBIN;
    }
}
