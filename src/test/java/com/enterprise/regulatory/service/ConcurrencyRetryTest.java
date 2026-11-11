package com.enterprise.regulatory.service;

import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyRetryTest {
    
    private final ConcurrencyRetry retry = new ConcurrencyRetry();
    
    private static RuntimeException conflict() {
        return new ObjectOptimisticLockingFailureException("RegulatoryRequest", "id");
    }
    
    @Test
    void retriesAConflictUntilItSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        String result = retry.call(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw conflict();
            }
            return "done";
        });
        assertThat(result).isEqualTo("done");
        assertThat(attempts).hasValue(3);
    }
    
    @Test
    void givesUpAfterTheLastAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> retry.run(() -> {
            attempts.incrementAndGet();
            throw conflict();
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(attempts).hasValue(ConcurrencyRetry.MAX_ATTEMPTS);
    }
    
    @Test
    void recognisesAWrappedConflict() {
        assertThat(ConcurrencyRetry.isOptimisticLockFailure(new IllegalStateException("wrapped", conflict()))).isTrue();
        assertThat(ConcurrencyRetry.isOptimisticLockFailure(new IllegalStateException("wrapped",
            new org.camunda.bpm.engine.OptimisticLockingException("engine conflict")))).isTrue();
        assertThat(ConcurrencyRetry.isOptimisticLockFailure(new IllegalStateException("other"))).isFalse();
    }
    
    @Test
    void doesNotRetryOtherFailures() {
        AtomicInteger attempts = new AtomicInteger();
        assertThatThrownBy(() -> retry.run(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("bad input");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(attempts).hasValue(1);
    }
}
