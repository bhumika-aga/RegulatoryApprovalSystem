package com.enterprise.regulatory.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;

@Component
@Slf4j
public class JwtTokenProvider {
    
    /**
     * HS512 requires a key of at least 512 bits.
     */
    private static final int MIN_KEY_BYTES = 64;
    
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
    
    public String generateToken(String username, List<String> roles, String department) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtProperties.getExpirationMs());
        
        return Jwts.builder()
                   .subject(username)
                   .issuer(jwtProperties.getIssuer())
                   .issuedAt(now)
                   .expiration(expiryDate)
                   .claim("roles", roles)
                   .claim("department", department)
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
                   .claim("type", "refresh")
                   .signWith(signingKey, Jwts.SIG.HS512)
                   .compact();
    }
    
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getSubject();
    }
    
    @SuppressWarnings("unchecked")
    public List<String> getRolesFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("roles", List.class);
    }
    
    public String getDepartmentFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get("department", String.class);
    }
    
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (SignatureException ex) {
            log.error("Invalid JWT signature: {}", ex.getMessage());
        } catch (MalformedJwtException ex) {
            log.error("Invalid JWT token: {}", ex.getMessage());
        } catch (ExpiredJwtException ex) {
            log.error("Expired JWT token: {}", ex.getMessage());
        } catch (UnsupportedJwtException ex) {
            log.error("Unsupported JWT token: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.error("JWT claims string is empty: {}", ex.getMessage());
        }
        return false;
    }
    
    private Claims parseToken(String token) {
        return Jwts.parser()
                   .verifyWith(signingKey)
                   .build()
                   .parseSignedClaims(token)
                   .getPayload();
    }
}
