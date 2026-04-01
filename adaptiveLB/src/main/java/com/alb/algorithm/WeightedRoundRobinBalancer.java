package com.alb.algorithm;

import com.alb.server.BackendServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class WeightedRoundRobinBalancer implements LoadBalancer {

    private final AtomicInteger index = new AtomicInteger(0);
    private volatile List<BackendServer> weightedList = new ArrayList<>();
    private volatile List<BackendServer> lastSeenServers = new ArrayList<>();

    @Override
    public BackendServer select(List<BackendServer> servers, String clientInfo) {
        List<BackendServer> healthy = servers.stream().filter(BackendServer::isHealthy).toList();
        if (healthy.isEmpty()) return null;

        if (!healthy.equals(lastSeenServers)) {
            rebuildWeightedList(healthy);
        }

        List<BackendServer> list = weightedList;
        if (list.isEmpty()) return null;
        return list.get(Math.abs(index.getAndIncrement() % list.size()));
    }

    private synchronized void rebuildWeightedList(List<BackendServer> servers) {
        List<BackendServer> newList = new ArrayList<>();
        for (BackendServer server : servers) {
            int weight = Math.max(1, server.getWeight());
            for (int i = 0; i < weight; i++) {
                newList.add(server);
            }
        }
        weightedList = newList;
        lastSeenServers = new ArrayList<>(servers);
        index.set(0);
    }

    @Override
    public AlgorithmType getType() {
        return AlgorithmType.WEIGHTED_ROUND_ROBIN;
    }
}
