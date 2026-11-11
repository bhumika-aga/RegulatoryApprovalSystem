package com.enterprise.regulatory.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {
    
    private static final String CLIENT = "203.0.113.7";
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }
        
        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
        
        @Override
        public Instant instant() {
            return now;
        }
    });
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    
    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(CLIENT);
        }
    }
    
    @Test
    void allowsAttemptsBelowTheLimit() {
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);
        assertThat(limiter.retryAfterSeconds(CLIENT)).isZero();
    }
    
    @Test
    void blocksAClientAtTheLimitUntilItsWindowEnds() {
        fail(LoginAttemptLimiter.MAX_FAILURES);
        assertThat(limiter.retryAfterSeconds(CLIENT)).isEqualTo(LoginAttemptLimiter.WINDOW.toSeconds());
        
        now = now.plus(LoginAttemptLimiter.WINDOW).minusSeconds(1);
        assertThat(limiter.retryAfterSeconds(CLIENT)).isEqualTo(1);
        
        now = now.plusSeconds(1);
        assertThat(limiter.retryAfterSeconds(CLIENT)).isZero();
    }
    
    @Test
    void countsFailuresPerClient() {
        fail(LoginAttemptLimiter.MAX_FAILURES);
        assertThat(limiter.retryAfterSeconds("198.51.100.1")).isZero();
    }
    
    @Test
    void startsANewWindowAfterTheOldOneEnds() {
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);
        now = now.plus(LoginAttemptLimiter.WINDOW);
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);
        assertThat(limiter.retryAfterSeconds(CLIENT)).isZero();
        
        now = now.plus(Duration.ofSeconds(1));
        fail(1);
        assertThat(limiter.retryAfterSeconds(CLIENT)).isPositive();
    }
}
