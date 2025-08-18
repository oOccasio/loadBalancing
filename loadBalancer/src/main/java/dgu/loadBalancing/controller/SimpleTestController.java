package dgu.loadBalancing.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 간단한 테스트 컨트롤러
 */
@RestController
@RequestMapping("/simple")
public class SimpleTestController {

    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of(
            "message", "Hello from Load Balancer!",
            "status", "OK"
        );
    }

    @GetMapping("/test")
    public Map<String, Object> test() {
        return Map.of(
            "service", "Load Balancer",
            "version", "1.0.0",
            "swagger", "http://localhost:8080/swagger-ui.html"
        );
    }
}
