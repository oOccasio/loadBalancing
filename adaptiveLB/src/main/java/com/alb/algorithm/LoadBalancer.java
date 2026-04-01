package com.alb.algorithm;

import com.alb.server.BackendServer;

import java.util.List;

public interface LoadBalancer {

    /**
     * Select a backend server from the given pool.
     *
     * @param servers    pool of available servers (may include unhealthy ones)
     * @param clientInfo client identifier, e.g. IP address (used by IP-Hash)
     * @return selected server, or null if no healthy server is available
     */
    BackendServer select(List<BackendServer> servers, String clientInfo);

    AlgorithmType getType();

    /**
     * Called after a request completes so the algorithm can update its internal state.
     */
    default void onRequestComplete(BackendServer server, long latencyMs, boolean success) {}
}
