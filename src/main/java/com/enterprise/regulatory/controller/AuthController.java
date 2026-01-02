package com.enterprise.regulatory.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.regulatory.dto.request.AuthRequest;
import com.enterprise.regulatory.dto.response.ApiResponse;
import com.enterprise.regulatory.dto.response.AuthResponse;
import com.enterprise.regulatory.security.JwtProperties;
import com.enterprise.regulatory.security.JwtTokenProvider;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Authentication controller for generating JWT tokens.
 * In production, this would integrate with an external IdP.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "JWT token generation endpoints")
public class AuthController {

        private final JwtTokenProvider tokenProvider;
        private final JwtProperties jwtProperties;

        @PostMapping("/token")
        @Operation(summary = "Generate JWT token", description = "Generate a JWT token for testing purposes")
        public ResponseEntity<ApiResponse<AuthResponse>> generateToken(@Valid @RequestBody AuthRequest request) {
                log.info("Generating token for user: {}", request.getUsername());

                List<String> roles = request.getRoles() != null && !request.getRoles().isEmpty()
                                ? request.getRoles()
                                : List.of("REVIEWER");

                String accessToken = tokenProvider.generateToken(
                                request.getUsername(),
                                roles,
                                request.getDepartment());

                String refreshToken = tokenProvider.generateRefreshToken(request.getUsername());

                AuthResponse response = AuthResponse.builder()
                                .accessToken(accessToken)
                                .refreshToken(refreshToken)
                                .tokenType("Bearer")
                                .expiresIn(jwtProperties.getExpirationMs() / 1000)
                                .username(request.getUsername())
                                .roles(roles)
                                .department(request.getDepartment())
                                .build();

                return ResponseEntity.ok(ApiResponse.success(response, "Token generated successfully"));
        }

        @PostMapping("/refresh")
        @Operation(summary = "Refresh JWT token", description = "Refresh an expired JWT token")
        public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(
                        @RequestHeader("X-Refresh-Token") String refreshToken) {
                if (!tokenProvider.validateToken(refreshToken)) {
                        return ResponseEntity.badRequest()
                                        .body(ApiResponse.error("Invalid or expired refresh token"));
                }

                String username = tokenProvider.getUsernameFromToken(refreshToken);
                List<String> roles = tokenProvider.getRolesFromToken(refreshToken);
                String department = tokenProvider.getDepartmentFromToken(refreshToken);

                String newAccessToken = tokenProvider.generateToken(username, roles, department);
                String newRefreshToken = tokenProvider.generateRefreshToken(username);

                AuthResponse response = AuthResponse.builder()
                                .accessToken(newAccessToken)
                                .refreshToken(newRefreshToken)
                                .tokenType("Bearer")
                                .expiresIn(jwtProperties.getExpirationMs() / 1000)
                                .username(username)
                                .roles(roles)
                                .department(department)
                                .build();

                return ResponseEntity.ok(ApiResponse.success(response, "Token refreshed successfully"));
        }
}
