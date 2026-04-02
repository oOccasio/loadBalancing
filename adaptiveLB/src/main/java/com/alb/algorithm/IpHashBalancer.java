package com.alb.algorithm;

import com.alb.server.BackendServer;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consistent IP-hash: same client IP always goes to the same server (session affinity).
 * Remaps only when the cached server becomes unhealthy.
 */
public class IpHashBalancer implements LoadBalancer {

    private final ConcurrentHashMap<String, String> ipServerMap = new ConcurrentHashMap<>();

    @Override
    public BackendServer select(List<BackendServer> servers, String clientInfo) {
        List<BackendServer> healthy = servers.stream().filter(BackendServer::isHealthy).toList();
        if (healthy.isEmpty()) return null;

        String selectedId = ipServerMap.compute(clientInfo, (ip, cachedId) -> {
            if (cachedId != null && healthy.stream().anyMatch(s -> s.getId().equals(cachedId))) {
                return cachedId;
            }
            int hash = calculateHash(ip);
            return healthy.get(Math.abs(hash) % healthy.size()).getId();
        });

        return healthy.stream().filter(s -> s.getId().equals(selectedId)).findFirst()
                .orElse(healthy.get(0));
    }

    private int calculateHash(String ip) {
        if (ip == null || ip.isBlank()) return 0;
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            try {
                long hash = 0;
                for (String part : parts) {
                    hash = hash * 256 + Integer.parseInt(part);
                }
                return (int) (hash & Integer.MAX_VALUE);
            } catch (NumberFormatException ignored) {
            }
        }
        return ip.hashCode();
    }

    @Override
    public AlgorithmType getType() {
        return AlgorithmType.IP_HASH;
    }
}
