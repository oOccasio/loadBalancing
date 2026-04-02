package com.alb.algorithm;

import com.alb.server.BackendServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class WeightedRoundRobinBalancer implements LoadBalancer {

    private final AtomicInteger index = new AtomicInteger(0);

    private volatile List<BackendServer> weightedList = List.of();
    private volatile List<BackendServer> lastSeenServers = List.of();

    @Override
    public BackendServer select(List<BackendServer> servers, String clientInfo) {
        List<BackendServer> healthy = servers.stream()
                .filter(BackendServer::isHealthy)
                .toList();

        if (healthy.isEmpty()) return null;

        if (!healthy.equals(lastSeenServers)) {
            rebuildWeightedList(healthy);
        }

        List<BackendServer> list = weightedList;
        if (list.isEmpty()) return null;

        int idx = Math.floorMod(index.getAndIncrement(), list.size());
        return list.get(idx);
    }

    private synchronized void rebuildWeightedList(List<BackendServer> servers) {
        if (servers.equals(lastSeenServers)) return;

        List<BackendServer> newList = new ArrayList<>();

        for (BackendServer server : servers) {
            int weight = Math.max(1, server.getWeight());
            for (int i = 0; i < weight; i++) {
                newList.add(server);
            }
        }

        weightedList = List.copyOf(newList);
        lastSeenServers = List.copyOf(servers);

        index.set(0);
    }

    @Override
    public AlgorithmType getType() {
        return AlgorithmType.WEIGHTED_ROUND_ROBIN;
    }
}