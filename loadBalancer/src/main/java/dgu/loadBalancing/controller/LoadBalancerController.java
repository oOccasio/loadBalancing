package dgu.loadBalancing.controller;

import dgu.loadBalancing.model.Server;
import dgu.loadBalancing.service.HealthCheckService;
import dgu.loadBalancing.strategy.LoadBalancingStrategy;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 메인 로드밸런서 컨트롤러
 */
@RestController
@Tag(name = "Load Balancer", description = "메인 로드밸런싱 API")
public class LoadBalancerController {
    
    @Autowired
    private WebClient webClient;
    
    @Autowired
    private HealthCheckService healthCheckService;
    
    @Autowired
    private Map<String, LoadBalancingStrategy> strategies;
    
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    
    /**
     * 모든 요청을 백엔드 서버로 프록시
     */
    @Operation(
        summary = "백엔드 서버로 요청 프록시", 
        description = "지정된 로드밸런싱 알고리즘을 사용하여 요청을 적절한 백엔드 서버로 전달합니다."
    )
    @Parameter(
        name = "algorithm", 
        description = "로드밸런싱 알고리즘", 
        required = false,
        example = "roundRobin"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "요청 성공"),
        @ApiResponse(responseCode = "400", description = "잘못된 알고리즘 이름"),
        @ApiResponse(responseCode = "502", description = "백엔드 서버 오류"),
        @ApiResponse(responseCode = "503", description = "사용 가능한 서버 없음")
    })
    @RequestMapping(value = "/api/**", method = {RequestMethod.GET, RequestMethod.POST, 
                    RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<?> proxyRequest(
            @RequestParam(value = "algorithm", defaultValue = "roundRobin") String algorithmName,
            HttpServletRequest request) {
        
        long requestStartTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            // 클라이언트 정보 추출
            String clientInfo = getClientInfo(request);
            
            // 로드밸런싱 전략 선택
            LoadBalancingStrategy strategy = strategies.get(algorithmName);
            if (strategy == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "알 수 없는 알고리즘: " + algorithmName,
                                   "available", strategies.keySet()));
            }
            
            // 건강한 서버 목록 가져오기
            List<Server> healthyServers = healthCheckService.getHealthyServers();
            if (healthyServers.isEmpty()) {
                failedRequests.incrementAndGet();
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("error", "사용 가능한 건강한 서버가 없습니다."));
            }
            
            // 서버 선택
            Server selectedServer = strategy.selectServer(healthyServers, clientInfo);
            
            // 요청 경로 추출 (프록시용)
            String requestPath = extractRequestPath(request);
            String targetUrl = selectedServer.getUrl() + requestPath;
            
            // 쿼리 파라미터 추가 (algorithm 제외)
            String queryString = buildQueryString(request, algorithmName);
            if (!queryString.isEmpty()) {
                targetUrl += "?" + queryString;
            }
            
            System.out.printf("[LoadBalancer] %s → %s (%s)%n", 
                    clientInfo, selectedServer.getId(), strategy.getStrategyName());
            
            // 백엔드 서버로 요청 전달
            return forwardRequest(selectedServer, targetUrl, strategy, requestStartTime);
            
        } catch (Exception e) {
            failedRequests.incrementAndGet();
            System.err.printf("[LoadBalancer] 요청 처리 실패: %s%n", e.getMessage());
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "내부 서버 오류", "message", e.getMessage()));
        }
    }
    
    /**
     * 백엔드 서버로 요청 전달
     */
    private ResponseEntity<?> forwardRequest(Server server, String targetUrl, 
                                           LoadBalancingStrategy strategy, long startTime) {
        try {
            // HTTP GET 요청 (실제로는 더 복잡한 프록시 로직 필요)
            String response = webClient.get()
                    .uri(targetUrl)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            
            long responseTime = System.currentTimeMillis() - startTime;
            
            // 메트릭 업데이트
            strategy.updateServerMetrics(server, responseTime, true);
            successfulRequests.incrementAndGet();
            
            return ResponseEntity.ok(response);
            
        } catch (WebClientException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            
            // 실패 메트릭 업데이트
            strategy.updateServerMetrics(server, responseTime, false);
            failedRequests.incrementAndGet();
            
            System.err.printf("[LoadBalancer] 서버 요청 실패: %s → %s%n", 
                    server.getId(), e.getMessage());
            
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "백엔드 서버 오류", 
                               "server", server.getId(),
                               "message", e.getMessage()));
        }
    }
    
    /**
     * 클라이언트 정보 추출
     */
    private String getClientInfo(HttpServletRequest request) {
        // IP 주소 우선, 없으면 세션 ID
        String clientIp = request.getHeader("X-Forwarded-For");
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getHeader("X-Real-IP");
        }
        if (clientIp == null || clientIp.isEmpty()) {
            clientIp = request.getRemoteAddr();
        }
        
        return clientIp != null ? clientIp : "unknown-client";
    }
    
    /**
     * 요청 경로 추출
     */
    private String extractRequestPath(HttpServletRequest request) {
        String requestURI = request.getRequestURI();
        // /api 제거
        return requestURI.startsWith("/api") ? requestURI.substring(4) : requestURI;
    }
    
    /**
     * 쿼리 스트링 구성 (algorithm 파라미터 제외)
     */
    private String buildQueryString(HttpServletRequest request, String algorithmName) {
        Map<String, String[]> params = request.getParameterMap();
        StringBuilder queryString = new StringBuilder();
        
        for (Map.Entry<String, String[]> entry : params.entrySet()) {
            String key = entry.getKey();
            if ("algorithm".equals(key)) continue; // algorithm 파라미터 제외
            
            for (String value : entry.getValue()) {
                if (queryString.length() > 0) {
                    queryString.append("&");
                }
                queryString.append(key).append("=").append(value);
            }
        }
        
        return queryString.toString();
    }
    
    /**
     * 로드밸런서 상태 및 통계
     */
    @Operation(
        summary = "로드밸런서 상태 조회", 
        description = "로드밸런서의 전체 통계와 서버 상태를 조회합니다."
    )
    @ApiResponse(responseCode = "200", description = "상태 조회 성공")
    @GetMapping("/lb/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // 기본 통계
        status.put("totalRequests", totalRequests.get());
        status.put("successfulRequests", successfulRequests.get());
        status.put("failedRequests", failedRequests.get());
        status.put("successRate", calculateSuccessRate());
        
        // 서버 상태
        status.put("healthSummary", healthCheckService.getHealthSummary());
        
        // 사용 가능한 알고리즘
        status.put("availableAlgorithms", strategies.keySet());
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * 성공률 계산
     */
    private double calculateSuccessRate() {
        long total = totalRequests.get();
        if (total == 0) return 0.0;
        
        return (double) successfulRequests.get() / total * 100.0;
    }
    
    /**
     * 로드밸런서 헬스체크
     */
    @Operation(
        summary = "로드밸런서 헬스체크", 
        description = "로드밸런서와 백엔드 서버들의 건강 상태를 조회합니다."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "정상 상태"),
        @ApiResponse(responseCode = "503", description = "서비스 사용 불가")
    })
    @GetMapping("/lb/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        List<Server> healthyServers = healthCheckService.getHealthyServers();
        
        Map<String, Object> health = new HashMap<>();
        health.put("status", healthyServers.isEmpty() ? "DOWN" : "UP");
        health.put("healthyServers", healthyServers.size());
        health.put("totalServers", strategies.size());
        
        HttpStatus status = healthyServers.isEmpty() ? 
                HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        
        return ResponseEntity.status(status).body(health);
    }
}
