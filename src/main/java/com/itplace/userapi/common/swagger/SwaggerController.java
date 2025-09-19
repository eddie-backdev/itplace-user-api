package com.itplace.userapi.common.swagger;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SwaggerController {
    @GetMapping("/api-docs")
    public String redirectToSwagger() {
        return "redirect:/swagger-ui/index.html";
    }
}
