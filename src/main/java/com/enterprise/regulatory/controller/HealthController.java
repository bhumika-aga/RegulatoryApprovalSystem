package com.enterprise.regulatory.controller;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Health check controller.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health", description = "Application health endpoints")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Health check", description = "Returns application health status")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "application", "Regulatory Approval System",
                "timestamp", LocalDateTime.now().toString()));
    }
}
