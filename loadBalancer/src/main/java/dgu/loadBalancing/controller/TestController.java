package dgu.loadBalancing.controller;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 로드밸런서 테스트용 컨트롤러
 */
@RestController
@RequestMapping("/test")
@Tag(name = "Test", description = "로드밸런서 테스트 API")
public class TestController {

    /**
     * Round Robin 테스트
     */
    @Operation(
        summary = "Round Robin 알고리즘 테스트", 
        description = "Round Robin 알고리즘으로 여러 번 요청을 보내서 순차적 분배를 확인합니다."
    )
    @Parameter(name = "count", description = "요청 횟수", example = "10")
    @GetMapping("/round-robin")
    public ResponseEntity<Map<String, Object>> testRoundRobin(
            @RequestParam(defaultValue = "10") int count) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("algorithm", "Round Robin");
        result.put("testCount", count);
        result.put("description", "Round Robin 알고리즘 테스트를 위해 /api/?algorithm=roundRobin 으로 " + count + "번 요청하세요.");
        result.put("expectedPattern", "server-1 → server-2 → server-3 → server-4 → server-1 (순환)");
        
        return ResponseEntity.ok(result);
    }

    /**
     * Weighted Round Robin 테스트
     */
    @Operation(
        summary = "Weighted Round Robin 알고리즘 테스트", 
        description = "Weighted Round Robin 알고리즘으로 가중치 기반 분배를 확인합니다."
    )
    @Parameter(name = "count", description = "요청 횟수", example = "20")
    @GetMapping("/weighted-round-robin")
    public ResponseEntity<Map<String, Object>> testWeightedRoundRobin(
            @RequestParam(defaultValue = "20") int count) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("algorithm", "Weighted Round Robin");
        result.put("testCount", count);
        result.put("description", "Weighted Round Robin 알고리즘 테스트를 위해 /api/?algorithm=weightedRoundRobin 으로 " + count + "번 요청하세요.");
        result.put("expectedRatio", "server-1(40%) : server-2(30%) : server-3(20%) : server-4(10%)");
        
        return ResponseEntity.ok(result);
    }

    /**
     * IP Hash 테스트
     */
    @Operation(
        summary = "IP Hash 알고리즘 테스트", 
        description = "IP Hash 알고리즘으로 같은 클라이언트가 같은 서버로 가는지 확인합니다."
    )
    @Parameter(name = "clientIp", description = "테스트할 클라이언트 IP", example = "192.168.1.100")
    @GetMapping("/ip-hash")
    public ResponseEntity<Map<String, Object>> testIpHash(
            @RequestParam(defaultValue = "192.168.1.100") String clientIp) {
        
        Map<String, Object> result = new HashMap<>();
        result.put("algorithm", "IP Hash");
        result.put("clientIp", clientIp);
        result.put("description", "IP Hash 알고리즘 테스트를 위해 X-Forwarded-For 헤더를 " + clientIp + "로 설정하여 /api/?algorithm=ipHash 로 여러 번 요청하세요.");
        result.put("expectedBehavior", "같은 IP에서 오는 모든 요청은 항상 같은 서버로 라우팅됩니다.");
        result.put("curlExample", "curl -H \"X-Forwarded-For: " + clientIp + "\" \"http://localhost:8080/api/?algorithm=ipHash\"");
        
        return ResponseEntity.ok(result);
    }

    /**
     * 모든 알고리즘 성능 비교 테스트
     */
    @Operation(
        summary = "전체 알고리즘 성능 비교", 
        description = "모든 로드밸런싱 알고리즘의 성능을 비교하기 위한 테스트 가이드를 제공합니다."
    )
    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> performanceTest() {
        
        Map<String, Object> result = new HashMap<>();
        result.put("title", "로드밸런싱 알고리즘 성능 비교 테스트");
        
        Map<String, String> algorithms = new HashMap<>();
        algorithms.put("roundRobin", "/api/?algorithm=roundRobin");
        algorithms.put("weightedRoundRobin", "/api/?algorithm=weightedRoundRobin");
        algorithms.put("leastConnections", "/api/?algorithm=leastConnections");
        algorithms.put("consistentHashing", "/api/?algorithm=consistentHashing");
        algorithms.put("ipHash", "/api/?algorithm=ipHash");
        algorithms.put("leastResponseTime", "/api/?algorithm=leastResponseTime");
        
        result.put("endpoints", algorithms);
        result.put("testCommand", "ab -n 100 -c 10 'http://localhost:8080/api/?algorithm=roundRobin'");
        result.put("description", "Apache Bench(ab)를 사용하여 각 알고리즘별로 부하 테스트를 실행하세요.");
        
        return ResponseEntity.ok(result);
    }

    /**
     * 장애 시나리오 테스트
     */
    @Operation(
        summary = "장애 시나리오 테스트", 
        description = "서버 장애 상황에서의 로드밸런서 동작을 테스트하기 위한 가이드를 제공합니다."
    )
    @GetMapping("/failure")
    public ResponseEntity<Map<String, Object>> failureTest() {
        
        Map<String, Object> result = new HashMap<>();
        result.put("title", "장애 시나리오 테스트");
        
        Map<String, String> steps = new HashMap<>();
        steps.put("step1", "POST /dashboard/servers/server-4/toggle - server-4 수동 다운");
        steps.put("step2", "GET /dashboard/servers - 서버 상태 확인");
        steps.put("step3", "GET /api/?algorithm=roundRobin - 3개 서버로만 분배되는지 확인");
        steps.put("step4", "POST /dashboard/servers/server-4/toggle - server-4 복구");
        steps.put("step5", "GET /api/?algorithm=roundRobin - 4개 서버로 다시 분배되는지 확인");
        
        result.put("testSteps", steps);
        result.put("description", "위 단계를 순서대로 실행하여 장애 복구 시나리오를 테스트하세요.");
        
        return ResponseEntity.ok(result);
    }
}
