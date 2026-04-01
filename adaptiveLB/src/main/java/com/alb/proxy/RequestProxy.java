package com.alb.proxy;

import com.alb.algorithm.AlgorithmType;
import com.alb.engine.DecisionEngine;
import com.alb.engine.DecisionLog;
import com.alb.engine.DecisionResult;
import com.alb.metrics.MetricsCollector;
import com.alb.metrics.MetricsExporter;
import com.alb.metrics.MetricsSnapshot;
import com.alb.server.BackendServer;
import com.alb.server.ServerPool;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * Front-door proxy: receives every HTTP request, selects a backend via the
 * current algorithm, forwards the request, and records metrics.
 *
 * Two modes:
 *  - PROXY (default)   : actually forwards to the configured backend URLs
 *  - SIMULATE          : returns a synthetic response using per-server latency/error-rate config
 */
@RestController
public class RequestProxy {

    private static final Logger log = LoggerFactory.getLogger(RequestProxy.class);

    private final ServerPool serverPool;
    private final MetricsCollector metricsCollector;
    private final MetricsExporter metricsExporter;
    private final DecisionEngine decisionEngine;
    private final DecisionLog decisionLog;
    private final WebClient webClient;

    @Value("${alb.mode:SIMULATE}")
    private String mode;

    @Value("${alb.timeout-seconds:10}")
    private int timeoutSeconds;

    public RequestProxy(ServerPool serverPool,
                        MetricsCollector metricsCollector,
                        MetricsExporter metricsExporter,
                        DecisionEngine decisionEngine,
                        DecisionLog decisionLog,
                        WebClient.Builder webClientBuilder) {
        this.serverPool = serverPool;
        this.metricsCollector = metricsCollector;
        this.metricsExporter = metricsExporter;
        this.decisionEngine = decisionEngine;
        this.decisionLog = decisionLog;
        this.webClient = webClientBuilder.build();
    }

    // ── Admin endpoints ─────────────────────────────────────────────────────────

    @GetMapping("/alb/status")
    public Map<String, Object> status() {
        return Map.of(
                "algorithm", serverPool.getCurrentAlgorithmType(),
                "mode", mode,
                "servers", serverPool.getServers().stream().map(s -> Map.of(
                        "id", s.getId(),
                        "url", s.getUrl(),
                        "healthy", s.isHealthy(),
                        "activeConnections", s.getActiveConnections(),
                        "totalRequests", s.getTotalRequests(),
                        "avgLatencyMs", s.getAverageLatencyMs(),
                        "errorRate", s.getErrorRate()
                )).toList()
        );
    }

    @PostMapping("/alb/algorithm")
    public Map<String, String> switchAlgorithm(@RequestParam AlgorithmType type) {
        serverPool.switchAlgorithm(type);
        return Map.of("algorithm", type.name());
    }

    @GetMapping("/alb/metrics")
    public List<MetricsSnapshot> metrics() {
        return metricsCollector.getSnapshots();
    }

    @GetMapping("/alb/metrics/latest")
    public MetricsSnapshot latestMetrics() {
        return metricsCollector.getLatestSnapshot();
    }

    @PostMapping("/alb/export")
    public Map<String, String> exportMetrics() throws IOException {
        String path = metricsExporter.exportToJson();
        return Map.of("file", path);
    }

    @GetMapping("/alb/decisions")
    public List<DecisionResult> decisions(@RequestParam(defaultValue = "20") int last) {
        return decisionLog.getLast(last);
    }

    @GetMapping("/alb/state")
    public Map<String, Object> engineState() {
        return Map.of(
                "currentState", decisionEngine.getCurrentState(),
                "currentAlgorithm", serverPool.getCurrentAlgorithmType(),
                "rules", decisionEngine.getRules().entrySet().stream()
                        .map(e -> Map.of(
                                "state", e.getKey(),
                                "algorithm", e.getValue().getAlgorithm(),
                                "confidenceThreshold", e.getValue().getConfidenceThreshold(),
                                "description", e.getValue().getDescription()
                        )).toList()
        );
    }

    @PostMapping("/alb/rules/reload")
    public Map<String, String> reloadRules() {
        decisionEngine.reloadRules();
        return Map.of("status", "Rules reloaded");
    }

    // ── Proxy handler ────────────────────────────────────────────────────────────

    /**
     * Catch-all: proxy any request that isn't an /alb/* admin endpoint.
     */
    @RequestMapping(value = "/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        String clientIp = resolveClientIp(request);
        BackendServer server = serverPool.selectServer(clientIp);

        if (server == null) {
            log.warn("No healthy server available for client {}", clientIp);
            return ResponseEntity.status(503).body("No healthy server available".getBytes());
        }

        server.incrementConnections();
        long start = System.currentTimeMillis();
        boolean success = true;

        try {
            if ("SIMULATE".equalsIgnoreCase(mode)) {
                return simulateResponse(server, start, clientIp);
            } else {
                return forwardRequest(request, server, body, start, clientIp);
            }
        } catch (Exception e) {
            success = false;
            log.error("Request to {} failed: {}", server.getId(), e.getMessage());
            return ResponseEntity.status(502).body(("Bad gateway: " + e.getMessage()).getBytes());
        } finally {
            long latency = System.currentTimeMillis() - start;
            serverPool.onRequestComplete(server, latency, success);
            metricsCollector.record(server.getId(), latency, success);
            log.debug("[{}] {} {} -> {} latency={}ms success={}",
                    serverPool.getCurrentAlgorithmType(), request.getMethod(),
                    request.getRequestURI(), server.getId(), latency, success);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private ResponseEntity<byte[]> forwardRequest(HttpServletRequest request,
                                                   BackendServer server,
                                                   byte[] body,
                                                   long start,
                                                   String clientIp) {
        String targetUrl = server.getUrl() + request.getRequestURI();
        if (request.getQueryString() != null) {
            targetUrl += "?" + request.getQueryString();
        }

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        WebClient.RequestBodySpec req = webClient.method(method).uri(targetUrl);

        // Forward original headers (skip hop-by-hop)
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (!isHopByHop(name)) {
                req.header(name, request.getHeader(name));
            }
        }
        req.header("X-Forwarded-For", clientIp);

        ResponseEntity<byte[]> response = req
                .bodyValue(body != null ? body : new byte[0])
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp -> Mono.empty())
                .toEntity(byte[].class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        return response != null ? response : ResponseEntity.status(502).build();
    }

    private ResponseEntity<byte[]> simulateResponse(BackendServer server, long start, String clientIp) throws InterruptedException {
        // Simulate configured latency
        if (server.getSimulatedLatencyMs() > 0) {
            Thread.sleep(server.getSimulatedLatencyMs());
        }

        // Simulate configured error rate
        if (Math.random() < server.getConfiguredErrorRate()) {
            return ResponseEntity.status(500)
                    .body(String.format("Simulated error from %s", server.getId()).getBytes());
        }

        String body = String.format(
                "{\"server\":\"%s\",\"latency\":%d,\"algorithm\":\"%s\"}",
                server.getId(),
                System.currentTimeMillis() - start,
                serverPool.getCurrentAlgorithmType()
        );
        return ResponseEntity.ok(body.getBytes());
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean isHopByHop(String headerName) {
        return switch (headerName.toLowerCase()) {
            case "connection", "keep-alive", "proxy-authenticate",
                    "proxy-authorization", "te", "trailers",
                    "transfer-encoding", "upgrade" -> true;
            default -> false;
        };
    }
}
