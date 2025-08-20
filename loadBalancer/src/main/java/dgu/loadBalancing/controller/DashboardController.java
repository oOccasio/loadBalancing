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

}
