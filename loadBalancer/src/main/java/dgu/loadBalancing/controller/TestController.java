package dgu.loadBalancing.controller;

import dgu.loadBalancing.model.Server;
import dgu.loadBalancing.strategy.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/server")
public class TestController {

    @Autowired
    private List<Server> backendServers;

    private final Map<String, LoadBalancingStrategy> strategies;

    public TestController() {
        // 사용 가능한 전략들 초기화
        strategies = new HashMap<>();
        strategies.put("ROUND_ROBIN", new RoundRobinStrategy());
        strategies.put("WEIGHTED_ROUND_ROBIN", new WeightedRoundRobinStrategy());
        strategies.put("LEAST_CONNECTIONS", new LeastConnectionsStrategy());
        strategies.put("LEAST_RESPONSE_TIME", new LeastResponseTimeStrategy());
        strategies.put("IP_HASH", new IpHashStrategy());
        strategies.put("CONSISTENT_HASHING", new ConsistentHashingStrategy());
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> handleRequest(
            @RequestParam(defaultValue = "ROUND_ROBIN") String algorithm,
            @RequestParam(required = false) String clientId,
            @RequestParam(required = false) String clientIp) {

        try {
            // 1. 알고리즘 검증
            if (!strategies.containsKey(algorithm.toUpperCase())) {
                return ResponseEntity.badRequest().body(
                    Map.of("error", "지원하지 않는 알고리즘입니다. 사용 가능한 알고리즘: " + 
                           String.join(", ", strategies.keySet()))
                );
            }

            // 2. 건강한 서버들만 필터링
            List<Server> healthyServers = backendServers.stream()
                    .filter(Server::isHealthy)
                    .collect(Collectors.toList());

            if (healthyServers.isEmpty()) {
                return ResponseEntity.status(503).body(
                    Map.of("error", "사용 가능한 서버가 없습니다.")
                );
            }

            // 3. 전략에 따라 서버 선택
            LoadBalancingStrategy strategy = strategies.get(algorithm.toUpperCase());
            String clientInfo = clientId != null ? clientId : 
                               (clientIp != null ? clientIp : "anonymous");
            
            Server selectedServer = strategy.selectServer(healthyServers, clientInfo);

            // 4. 서버 메트릭 시뮬레이션 (실제로는 서버에 요청을 보내고 응답시간 측정)
            selectedServer.incrementConnections();
            long responseTime = simulateServerRequest(selectedServer);
            selectedServer.recordResponseTime(responseTime);
            selectedServer.decrementConnections();

            // 5. 응답 구성
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "요청이 성공적으로 처리되었습니다");
            
            Map<String, Object> serverInfo = new HashMap<>();
            serverInfo.put("id", selectedServer.getId());
            serverInfo.put("url", selectedServer.getUrl());
            serverInfo.put("weight", selectedServer.getWeight());
            serverInfo.put("currentConnections", selectedServer.getCurrentConnections());
            serverInfo.put("totalRequests", selectedServer.getTotalRequests());
            serverInfo.put("averageResponseTime", String.format("%.2f ms", selectedServer.getAverageResponseTime()));
            
            response.put("selectedServer", serverInfo);
            response.put("algorithm", algorithm.toUpperCase());
            response.put("clientInfo", clientInfo);
            response.put("timestamp", LocalDateTime.now());
            response.put("responseTime", responseTime + " ms");

            return ResponseEntity.ok()
                    .header("X-Selected-Server", selectedServer.getId())
                    .header("X-Load-Balancer", "Active")
                    .header("X-Algorithm", algorithm.toUpperCase())
                    .body(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "서버 선택 중 오류가 발생했습니다: " + e.getMessage())
            );
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getServerStatus() {
        List<Map<String, Object>> serverStatuses = backendServers.stream()
                .map(server -> {
                    Map<String, Object> serverInfo = new HashMap<>();
                    serverInfo.put("id", server.getId());
                    serverInfo.put("url", server.getUrl());
                    serverInfo.put("healthy", server.isHealthy());
                    serverInfo.put("weight", server.getWeight());
                    serverInfo.put("currentConnections", server.getCurrentConnections());
                    serverInfo.put("totalRequests", server.getTotalRequests());
                    serverInfo.put("averageResponseTime", String.format("%.2f ms", server.getAverageResponseTime()));
                    return serverInfo;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("servers", serverStatuses);
        response.put("availableAlgorithms", strategies.keySet());
        response.put("timestamp", LocalDateTime.now());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/algorithms")
    public ResponseEntity<Map<String, Object>> getAvailableAlgorithms() {
        Map<String, String> algorithmDescriptions = new HashMap<>();
        strategies.forEach((name, strategy) -> 
            algorithmDescriptions.put(name, strategy.getDescription())
        );

        return ResponseEntity.ok(Map.of(
            "algorithms", algorithmDescriptions,
            "usage", "GET /api/server?algorithm={ALGORITHM_NAME}&clientId={CLIENT_ID}"
        ));
    }

    // 서버 요청 시뮬레이션 (실제로는 WebClient로 백엔드 서버에 요청)
    private long simulateServerRequest(Server server) {
        // 서버별 가중치에 따른 응답시간 시뮬레이션
        Random random = new Random();
        int baseTime = switch (server.getWeight()) {
            case 4 -> 50;   // 고성능 서버
            case 3 -> 100;  // 보통 성능
            case 2 -> 150;  // 낮은 성능
            default -> 200; // 매우 낮은 성능
        };
        
        return baseTime + random.nextInt(50); // ±25ms 변동
    }
}
