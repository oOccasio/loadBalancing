package dgu.loadBalancing.controller;

import dgu.loadBalancing.model.Server;
import dgu.loadBalancing.service.HealthCheckService;
import dgu.loadBalancing.strategy.*;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 모니터링 대시보드 컨트롤러
 */
@RestController
@RequestMapping("/dashboard")
@Tag(name = "Dashboard", description = "모니터링 대시보드 API")
public class DashboardController {
    
    @Autowired
    private List<Server> backendServers;
    
    @Autowired
    private HealthCheckService healthCheckService;
    
    @Autowired
    private Map<String, LoadBalancingStrategy> strategies;
    
    /**
     * 서버 상태 정보
     */
    @Operation(
        summary = "서버 상태 조회", 
        description = "모든 백엔드 서버의 상태 정보를 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "서버 상태 조회 성공")
    @GetMapping("/servers")
    public ResponseEntity<Map<String, Object>> getServerStatus() {
        Map<String, Object> response = new HashMap<>();
        
        for (Server server : backendServers) {
            Map<String, Object> serverInfo = new HashMap<>();
            serverInfo.put("id", server.getId());
            serverInfo.put("url", server.getUrl());
            serverInfo.put("healthy", server.isHealthy());
            serverInfo.put("weight", server.getWeight());
            serverInfo.put("currentConnections", server.getCurrentConnections());
            serverInfo.put("totalRequests", server.getTotalRequests());
            serverInfo.put("averageResponseTime", server.getAverageResponseTime());
            
            response.put(server.getId(), serverInfo);
        }
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * 알고리즘별 상세 정보
     */
    @Operation(
        summary = "알고리즘 상세 정보 조회", 
        description = "특정 로드밸런싱 알고리즘의 상세 정보를 조회합니다."
    )
    @Parameter(name = "algorithmName", description = "알고리즘 이름", required = true)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "알고리즘 정보 조회 성공"),
        @ApiResponse(responseCode = "404", description = "알고리즘을 찾을 수 없음")
    })
    @GetMapping("/algorithms/{algorithmName}")
    public ResponseEntity<Map<String, Object>> getAlgorithmDetails(
            @PathVariable String algorithmName) {
        
        LoadBalancingStrategy strategy = strategies.get(algorithmName);
        if (strategy == null) {
            return ResponseEntity.notFound().build();
        }
        
        Map<String, Object> details = new HashMap<>();
        details.put("name", strategy.getStrategyName());
        details.put("description", strategy.getDescription());
        
        // 알고리즘별 특화 정보
        if (strategy instanceof WeightedRoundRobinStrategy wrrStrategy) {
            details.put("weightDistribution", wrrStrategy.getWeightDistribution(backendServers));
            
        } else if (strategy instanceof LeastConnectionsStrategy lcStrategy) {
            details.put("connectionStatus", lcStrategy.getConnectionStatus(backendServers));
            details.put("totalRequestStats", lcStrategy.getTotalRequestStats(backendServers));
            
        } else if (strategy instanceof ConsistentHashingStrategy chStrategy) {
            details.put("ringDistribution", chStrategy.getRingDistribution());
            details.put("ringSize", chStrategy.getRingSize());
            
        } else if (strategy instanceof IpHashStrategy ipStrategy) {
            details.put("currentMappings", ipStrategy.getCurrentMappings());
            details.put("mappingCacheSize", ipStrategy.getMappingCacheSize());
            
        } else if (strategy instanceof LeastResponseTimeStrategy lrtStrategy) {
            details.put("responseTimeStatus", lrtStrategy.getResponseTimeStatus(backendServers));
            details.put("detailedStats", lrtStrategy.getDetailedStats());
        }
        
        return ResponseEntity.ok(details);
    }
    
    /**
     * 전체 알고리즘 목록
     */
    @Operation(
        summary = "사용 가능한 알고리즘 목록 조회", 
        description = "현재 등록된 모든 로드밸런싱 알고리즘의 목록을 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "알고리즘 목록 조회 성공")
    @GetMapping("/algorithms")
    public ResponseEntity<Map<String, Object>> getAllAlgorithms() {
        Map<String, Object> algorithms = new HashMap<>();
        
        for (Map.Entry<String, LoadBalancingStrategy> entry : strategies.entrySet()) {
            Map<String, String> algorithmInfo = new HashMap<>();
            algorithmInfo.put("name", entry.getValue().getStrategyName());
            algorithmInfo.put("description", entry.getValue().getDescription());
            
            algorithms.put(entry.getKey(), algorithmInfo);
        }
        
        return ResponseEntity.ok(algorithms);
    }
    
    /**
     * 수동 헬스체크 트리거
     */
    @Operation(
        summary = "헬스체크 수동 실행", 
        description = "모든 백엔드 서버에 대해 헬스체크를 즉시 실행합니다."
    )
    @ApiResponse(responseCode = "200", description = "헬스체크 실행 완료")
    @PostMapping("/healthcheck")
    public ResponseEntity<Map<String, String>> triggerHealthCheck() {
        healthCheckService.performHealthCheck();
        
        return ResponseEntity.ok(Map.of(
                "message", "헬스체크가 트리거되었습니다.",
                "status", healthCheckService.getHealthSummary()
        ));
    }
    
    /**
     * 서버 상태 수동 변경 (테스트용)
     */
    @Operation(
        summary = "서버 상태 토글 (테스트용)", 
        description = "특정 서버의 건강 상태를 수동으로 변경합니다. 테스트 목적으로만 사용하세요."
    )
    @Parameter(name = "serverId", description = "서버 ID", required = true)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "서버 상태 변경 성공"),
        @ApiResponse(responseCode = "404", description = "서버를 찾을 수 없음")
    })
    @PostMapping("/servers/{serverId}/toggle")
    public ResponseEntity<Map<String, Object>> toggleServerHealth(
            @PathVariable String serverId) {
        
        Server server = backendServers.stream()
                .filter(s -> s.getId().equals(serverId))
                .findFirst()
                .orElse(null);
        
        if (server == null) {
            return ResponseEntity.notFound().build();
        }
        
        // 상태 토글
        server.setHealthy(!server.isHealthy());
        
        Map<String, Object> response = new HashMap<>();
        response.put("serverId", serverId);
        response.put("newStatus", server.isHealthy() ? "HEALTHY" : "UNHEALTHY");
        response.put("message", String.format("%s 서버 상태가 %s로 변경되었습니다.", 
                serverId, server.isHealthy() ? "건강함" : "비활성"));
        
        return ResponseEntity.ok(response);
    }
}
