package com.enterprise.regulatory.controller;

import com.enterprise.regulatory.dto.request.AuthRequest;
import com.enterprise.regulatory.dto.response.ApiResponse;
import com.enterprise.regulatory.dto.response.AuthResponse;
import com.enterprise.regulatory.security.AuthUserService;
import com.enterprise.regulatory.security.AuthUserService.StoredUser;
import com.enterprise.regulatory.security.JwtProperties;
import com.enterprise.regulatory.security.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Authentication controller for issuing JWT tokens.
 *
 * <p>Credentials are verified against the configured user store
 * ({@link AuthUserService}). Roles and department are taken from that store, not
 * from the request, so a caller cannot grant itself privileges. In production
 * this would be replaced by an integration with an external IdP.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Authentication", description = "JWT token issuance endpoints")
public class AuthController {

    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final AuthUserService authUserService;

    @PostMapping("/token")
    @Operation(summary = "Obtain a JWT token", description = "Authenticate with username and password to obtain access and refresh tokens")
    public ResponseEntity<ApiResponse<AuthResponse>> generateToken(@Valid @RequestBody AuthRequest request) {
        log.info("Token requested for user: {}", request.getUsername());

        Optional<StoredUser> authenticated = authUserService.authenticate(
            request.getUsername(), request.getPassword());

        if (authenticated.isEmpty()) {
            log.warn("Failed authentication attempt for user: {}", request.getUsername());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                       .body(ApiResponse.error("Invalid username or password"));
        }

        StoredUser user = authenticated.get();
        return ResponseEntity.ok(ApiResponse.success(
            buildTokens(user), "Token generated successfully"));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh JWT token", description = "Exchange a valid refresh token for a new access + refresh token pair")
    public ResponseEntity<ApiResponse<AuthResponse>> refreshToken(
        @RequestHeader("X-Refresh-Token") String refreshToken) {
        if (!tokenProvider.validateToken(refreshToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                       .body(ApiResponse.error("Invalid or expired refresh token"));
        }

        String username = tokenProvider.getUsernameFromToken(refreshToken);

        // Re-derive roles and department from the current user store rather than from
        // the refresh token, so privileges reflect the user's present configuration.
        Optional<StoredUser> user = authUserService.findByUsername(username);
        if (user.isEmpty()) {
            log.warn("Refresh token presented for unknown user: {}", username);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                       .body(ApiResponse.error("User no longer exists"));
        }

        return ResponseEntity.ok(ApiResponse.success(
            buildTokens(user.get()), "Token refreshed successfully"));
    }

    private AuthResponse buildTokens(StoredUser user) {
        String accessToken = tokenProvider.generateToken(
            user.getUsername(), user.getRoles(), user.getDepartment());
        String refreshToken = tokenProvider.generateRefreshToken(user.getUsername());

        return AuthResponse.builder()
                   .accessToken(accessToken)
                   .refreshToken(refreshToken)
                   .tokenType("Bearer")
                   .expiresIn(jwtProperties.getExpirationMs() / 1000)
                   .username(user.getUsername())
                   .roles(user.getRoles())
                   .department(user.getDepartment())
                   .build();
    }
}
