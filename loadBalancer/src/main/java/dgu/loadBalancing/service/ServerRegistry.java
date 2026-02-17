package dgu.loadBalancing.service;

import dgu.loadBalancing.model.Server;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class ServerRegistry {

    private final List<Server> servers = new CopyOnWriteArrayList<>();
    private final LoadBalancerMetrics metrics;

    public ServerRegistry(LoadBalancerMetrics metrics) {
        this.metrics = metrics;
    }

    @PostConstruct
    public void init() {
        addServer(new Server("server-1", "http://localhost:5001", 6));
        addServer(new Server("server-2", "http://localhost:5002", 3));
        addServer(new Server("server-3", "http://localhost:5003", 2));
        addServer(new Server("server-4", "http://localhost:5004", 1));
    }

    public void addServer(Server server) {
        servers.add(server);
        metrics.registerServerHealth(server); // 딱 1번
    }

    public void removeServer(String serverId) {
        servers.removeIf(s -> s.getId().equals(serverId));
    }

    public List<Server> getServers() {
        return List.copyOf(servers);
    }

    public Server getServer(String serverId) {
        return servers.stream()
                .filter(s -> s.getId().equals(serverId))
                .findFirst()
                .orElse(null);
    }
    public List<Server> getHealthyServers() {
        return servers.stream()
                .filter(Server::isHealthy)
                .toList();
    }

    public void updateServerHealth(String serverId, boolean healthy) {
        Server server = getServer(serverId);
        if (server != null) {
            server.setHealthy(healthy);
        }
    }
}