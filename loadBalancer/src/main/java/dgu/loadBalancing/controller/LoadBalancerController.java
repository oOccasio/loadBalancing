package dgu.loadBalancing.controller;

import dgu.loadBalancing.service.LoadBalancerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class LoadBalancerController {

    private final LoadBalancerService loadBalancerService;

    public LoadBalancerController(LoadBalancerService loadBalancerService) {
        this.loadBalancerService = loadBalancerService;
    }

    @GetMapping("/test")
    public ResponseEntity<String> forward(HttpServletRequest request) {
        String path = request.getRequestURI().substring(1); // 앞의 "/" 제거
        return loadBalancerService.forwardRequest(path, request);
    }
}