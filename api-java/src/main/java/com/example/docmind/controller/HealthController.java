package com.example.docmind.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Health check liveness (/api/health & alias /actuator/health cho reverse proxy / probe). */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "docmind-api");
    }

    @GetMapping("/actuator/health")
    public Map<String, String> actuatorHealthAlias() {
        return Map.of("status", "UP", "service", "docmind-api");
    }
}
