package com.enterprise.regulatory.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class JwtTokenProvider {
    
    /**
     * HS512 requires a key of at least 512 bits.
     */
    private static final int MIN_KEY_BYTES = 64;
    
    /**
     * Distinguishes access tokens from refresh tokens, so neither can stand in for the other.
     */
    private static final String TYPE_CLAIM = "type";
    private static final String ACCESS_TYPE = "access";
    private static final String REFRESH_TYPE = "refresh";
    private static final String ROLES_CLAIM = "roles";
    private static final String DEPARTMENT_CLAIM = "department";
    
    private final JwtProperties jwtProperties;
    private final SecretKey signingKey;
    
    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        // Strip whitespace/newlines and convert URL-safe Base64 to standard Base64
        String cleanedSecret = jwtProperties.getSecretKey()
                                   .replaceAll("\\s+", "")  // Remove whitespace/newlines
                                   .replace('-', '+')        // URL-safe to standard Base64
                                   .replace('_', '/');       // URL-safe to standard Base64
        byte[] keyBytes;
        try {
            keyBytes = Decoders.BASE64.decode(cleanedSecret);
        } catch (RuntimeException e) {
            throw new IllegalStateException("JWT_SECRET must be Base64-encoded (generate one with: openssl rand -base64 64)", e);
        }
        // Fail at startup rather than on the first login, when signing would reject the key.
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SECRET must decode to at least " + MIN_KEY_BYTES
                                                + " bytes for HS512, got " + keyBytes.length
                                                + " (generate one with: openssl rand -base64 64)");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }
    
    @SuppressWarnings("unchecked")
    public static List<String> rolesOf(Claims claims) {
        List<String> roles = claims.get(ROLES_CLAIM, List.class);
        return roles != null ? roles : List.of();
    }
    
    public static String departmentOf(Claims claims) {
        return claims.get(DEPARTMENT_CLAIM, String.class);
    }
    
    public String generateToken(String username, List<String> roles, String department) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getExpirationMs());
        
        return Jwts.builder()
                   .subject(username)
                   .issuer(jwtProperties.getIssuer())
                   .issuedAt(now)
                   .expiration(expiryDate)
                   .claim(TYPE_CLAIM, ACCESS_TYPE)
                   .claim(ROLES_CLAIM, roles)
                   .claim(DEPARTMENT_CLAIM, department)
                   .signWith(signingKey, Jwts.SIG.HS512)
                   .compact();
    }
    
    public String generateRefreshToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getRefreshExpirationMs());
        
        return Jwts.builder()
                   .subject(username)
                   .issuer(jwtProperties.getIssuer())
                   .issuedAt(now)
                   .expiration(expiryDate)
                   .claim(TYPE_CLAIM, REFRESH_TYPE)
                   .signWith(signingKey, Jwts.SIG.HS512)
                   .compact();
    }
    
    /**
     * Verifies an access token and returns its claims. Empty if the token is invalid,
     * expired, or of another type: a refresh token is never accepted as an access token.
     */
    public Optional<Claims> parseAccessToken(String token) {
        return parseTokenOfType(token, ACCESS_TYPE);
    }
    
    /**
     * Verifies a refresh token and returns its claims. Empty if the token is invalid,
     * expired, or of another type: an access token cannot be exchanged for new tokens.
     */
    public Optional<Claims> parseRefreshToken(String token) {
        return parseTokenOfType(token, REFRESH_TYPE);
    }
    
    private Optional<Claims> parseTokenOfType(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                                .verifyWith(signingKey)
                                .build()
                                .parseSignedClaims(token)
                                .getPayload();
            String type = claims.get(TYPE_CLAIM, String.class);
            if (!expectedType.equals(type)) {
                log.warn("Rejected a JWT of type '{}' where a '{}' token is required", type, expectedType);
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (ExpiredJwtException ex) {
            log.debug("Expired JWT: {}", ex.getMessage());
        } catch (JwtException | IllegalArgumentException ex) {
            log.warn("Invalid JWT: {}", ex.getMessage());
        }
        return Optional.empty();
    }
}
