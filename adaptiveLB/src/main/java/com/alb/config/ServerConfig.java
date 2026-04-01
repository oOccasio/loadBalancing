package com.alb.config;

import com.alb.algorithm.AlgorithmType;
import com.alb.server.BackendServer;
import com.alb.server.ServerPool;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties(ServerConfig.AlbProperties.class)
public class ServerConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public ServerPool serverPool(AlbProperties props) {
        AlgorithmType algo = props.getAlgorithm() != null ? props.getAlgorithm() : AlgorithmType.ROUND_ROBIN;
        ServerPool pool = new ServerPool(algo);

        List<AlbProperties.ServerProperties> servers = props.getServers();
        if (servers == null || servers.isEmpty()) {
            // Default servers when none are configured in YAML
            pool.addServer(new BackendServer("server-1", "http://localhost:5001", 3, 50, 0.01));
            pool.addServer(new BackendServer("server-2", "http://localhost:5002", 2, 100, 0.02));
            pool.addServer(new BackendServer("server-3", "http://localhost:5003", 1, 200, 0.05));
        } else {
            for (AlbProperties.ServerProperties sp : servers) {
                pool.addServer(new BackendServer(
                        sp.getId(), sp.getUrl(), sp.getWeight(),
                        sp.getSimulatedLatencyMs(), sp.getErrorRate()
                ));
            }
        }
        return pool;
    }

    // ── Properties binding ───────────────────────────────────────────────────────

    @ConfigurationProperties(prefix = "alb")
    public static class AlbProperties {

        private AlgorithmType algorithm = AlgorithmType.ROUND_ROBIN;
        private String mode = "SIMULATE";
        private int timeoutSeconds = 10;
        private List<ServerProperties> servers = new ArrayList<>();

        public AlgorithmType getAlgorithm() { return algorithm; }
        public void setAlgorithm(AlgorithmType algorithm) { this.algorithm = algorithm; }
        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public List<ServerProperties> getServers() { return servers; }
        public void setServers(List<ServerProperties> servers) { this.servers = servers; }

        public static class ServerProperties {
            private String id;
            private String url;
            private int weight = 1;
            private long simulatedLatencyMs = 0;
            private double errorRate = 0.0;

            public String getId() { return id; }
            public void setId(String id) { this.id = id; }
            public String getUrl() { return url; }
            public void setUrl(String url) { this.url = url; }
            public int getWeight() { return weight; }
            public void setWeight(int weight) { this.weight = weight; }
            public long getSimulatedLatencyMs() { return simulatedLatencyMs; }
            public void setSimulatedLatencyMs(long simulatedLatencyMs) { this.simulatedLatencyMs = simulatedLatencyMs; }
            public double getErrorRate() { return errorRate; }
            public void setErrorRate(double errorRate) { this.errorRate = errorRate; }
        }
    }
}
