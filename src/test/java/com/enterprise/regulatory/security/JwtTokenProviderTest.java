package com.enterprise.regulatory.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JwtTokenProvider} key validation and token round-trip,
 * without a Spring context.
 */
class JwtTokenProviderTest {
    
    private static JwtProperties propertiesWithKeyOf(int bytes) {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey(Base64.getEncoder().encodeToString(new byte[bytes]));
        return properties;
    }
    
    @Test
    void rejectsKeysShorterThanHs512Requires() {
        assertThatThrownBy(() -> new JwtTokenProvider(propertiesWithKeyOf(32)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 64 bytes");
    }
    
    @Test
    void rejectsNonBase64Keys() {
        JwtProperties properties = new JwtProperties();
        properties.setSecretKey("not*base64*at*all");
        assertThatThrownBy(() -> new JwtTokenProvider(properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Base64");
    }
    
    @Test
    void issuesAndValidatesTokensWithA64ByteKey() {
        JwtTokenProvider provider = new JwtTokenProvider(propertiesWithKeyOf(64));
        String token = provider.generateToken("alice", List.of("REVIEWER"), "OPERATIONS");
        
        Claims claims = provider.parseAccessToken(token).orElseThrow();
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(JwtTokenProvider.rolesOf(claims)).containsExactly("REVIEWER");
        assertThat(JwtTokenProvider.departmentOf(claims)).isEqualTo("OPERATIONS");
    }
    
    @Test
    void accessAndRefreshTokensCannotStandInForEachOther() {
        JwtTokenProvider provider = new JwtTokenProvider(propertiesWithKeyOf(64));
        String access = provider.generateToken("alice", List.of("REVIEWER"), "OPERATIONS");
        String refresh = provider.generateRefreshToken("alice");
        
        assertThat(provider.parseRefreshToken(refresh)).isPresent();
        assertThat(provider.parseRefreshToken(access)).isEmpty();
        assertThat(provider.parseAccessToken(refresh)).isEmpty();
    }
    
    @Test
    void rejectsTokensSignedWithAnotherKey() {
        JwtProperties otherKey = new JwtProperties();
        otherKey.setSecretKey(Base64.getEncoder().encodeToString(new byte[64]).replace('A', 'B'));
        String foreign = new JwtTokenProvider(otherKey).generateToken("alice", List.of("ADMIN"), "IT");
        
        assertThat(new JwtTokenProvider(propertiesWithKeyOf(64)).parseAccessToken(foreign)).isEmpty();
    }
}
