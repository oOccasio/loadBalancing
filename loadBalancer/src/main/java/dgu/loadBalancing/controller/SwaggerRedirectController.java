package dgu.loadBalancing.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Swagger UI 리다이렉트 컨트롤러
 */
@Controller
public class SwaggerRedirectController {

    @GetMapping("/swagger")
    public String redirectToSwaggerAlias() {
        return "redirect:/swagger-ui/index.html";
    }

    @GetMapping("/swagger-ui")
    public String redirectToSwaggerUI() {
        return "redirect:/swagger-ui/index.html";
    }
}
