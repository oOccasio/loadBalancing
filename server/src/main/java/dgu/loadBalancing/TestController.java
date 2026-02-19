package dgu.loadBalancing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class TestController {

    @Value("${SERVER_ID:unknown}")
    private String serverId;

    @Value("${RESPONSE_DELAY:0}")
    private int delay;

    @GetMapping("/test")
    public ResponseEntity<String> test() throws InterruptedException {
        Thread.sleep(delay);

        return ResponseEntity.ok(
                "Response from " + serverId +
                        " (delay=" + delay + "ms)");
        }



    @GetMapping("/")
    public Map<String, Object> home() {
        // 환경변수에서 서버 설정 읽기
        String serverId = System.getenv("SERVER_ID");
        String responseDelay = System.getenv("RESPONSE_DELAY");

        // 응답 지연 시뮬레이션
        if (responseDelay != null) {
            try {
                Thread.sleep(Integer.parseInt(responseDelay));
            } catch (Exception e) {
                // 무시
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("server", serverId != null ? serverId : "unknown");
        response.put("timestamp", System.currentTimeMillis());
        response.put("responseDelay", responseDelay != null ? responseDelay + "ms" : "0ms");

        return response;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of(
                "status", "healthy",
                "server", System.getenv("SERVER_ID")
        );
    }
}
