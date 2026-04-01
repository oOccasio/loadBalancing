package com.alb.server;

import com.alb.algorithm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages the backend server pool and the currently active load balancing algorithm.
 * Created as a Spring bean by {@link com.alb.config.ServerConfig}.
 */
public class ServerPool {

    private static final Logger log = LoggerFactory.getLogger(ServerPool.class);

    private final List<BackendServer> servers = new CopyOnWriteArrayList<>();

    private volatile LoadBalancer currentBalancer;

    private final Map<AlgorithmType, LoadBalancer> balancers = Map.of(
            AlgorithmType.ROUND_ROBIN, new RoundRobinBalancer(),
            AlgorithmType.WEIGHTED_ROUND_ROBIN, new WeightedRoundRobinBalancer(),
            AlgorithmType.LEAST_CONNECTIONS, new LeastConnectionsBalancer(),
            AlgorithmType.WEIGHTED_LEAST_CONNECTIONS, new WeightedLeastConnectionsBalancer(),
            AlgorithmType.IP_HASH, new IpHashBalancer(),
            AlgorithmType.RANDOM, new RandomBalancer()
    );

    public ServerPool(AlgorithmType initialAlgorithm) {
        this.currentBalancer = balancers.get(initialAlgorithm);
        log.info("ServerPool initialized with algorithm={}", initialAlgorithm);
    }

    public void addServer(BackendServer server) {
        servers.add(server);
        log.info("Server added: {}", server);
    }

    public void removeServer(String serverId) {
        servers.removeIf(s -> s.getId().equals(serverId));
        log.info("Server removed: {}", serverId);
    }

    public BackendServer selectServer(String clientInfo) {
        return currentBalancer.select(servers, clientInfo);
    }

    public void onRequestComplete(BackendServer server, long latencyMs, boolean success) {
        server.recordResponse(latencyMs, success);
        currentBalancer.onRequestComplete(server, latencyMs, success);
    }

    public void switchAlgorithm(AlgorithmType type) {
        LoadBalancer next = balancers.get(type);
        if (next == null) {
            log.warn("Unknown algorithm type: {}", type);
            return;
        }
        log.info("Algorithm switching: {} -> {}", currentBalancer.getType(), type);
        currentBalancer = next;
    }

    public AlgorithmType getCurrentAlgorithmType() {
        return currentBalancer.getType();
    }

    public List<BackendServer> getServers() {
        return servers;
    }

    public List<BackendServer> getHealthyServers() {
        return servers.stream().filter(BackendServer::isHealthy).toList();
    }
}
