package dgu.loadBalancing.controller;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 홈 컨트롤러 - 기본 정보 제공
 */
@RestController
@Hidden // Swagger 문서에서 숨김
public class HomeController {

    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> home() {
        Map<String, Object> response = new HashMap<>();
        response.put("service", "DGU Load Balancer");
        response.put("version", "1.0.0");
        response.put("status", "UP");
        
        Map<String, String> links = new HashMap<>();
        links.put("swagger-ui", "/swagger-ui/index.html");
        links.put("api-docs", "/v3/api-docs");
        links.put("health", "/actuator/health");
        links.put("status", "/lb/status");
        links.put("servers", "/dashboard/servers");
        
        response.put("links", links);
        response.put("message", "로드밸런서가 정상적으로 실행 중입니다. Swagger UI는 /swagger-ui/index.html 에서 확인하세요.");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("application", "Load Balancer");
        info.put("description", "머신러닝 기반 지능형 로드밸런싱 시스템");
        info.put("algorithms", new String[]{
            "roundRobin", "weightedRoundRobin", "leastConnections", 
            "consistentHashing", "ipHash", "leastResponseTime"
        });
        
        return ResponseEntity.ok(info);
    }
}
