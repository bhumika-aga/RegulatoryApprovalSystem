package com.enterprise.regulatory.service;

import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Retries a transactional action that lost an optimistic-locking race.
 *
 * <p>A regulatory request is updated both by API calls and by the external task workers, so
 * two transactions can update the same row at once; the one that commits second fails on the
 * row's {@code @Version}. The engine has the same kind of check on its own rows (e.g. terminating
 * a workflow while a worker is locking its external task). Re-running the action from a fresh read
 * resolves both. The action must be a
 * whole transaction (a call through a {@code @Transactional} proxy or a
 * {@code TransactionTemplate}), so a failed attempt has been rolled back before the next starts.
 */
@Component
@Slf4j
public class ConcurrencyRetry {
    
    static final int MAX_ATTEMPTS = 3;
    
    /**
     * Whether {@code e}, or any exception it wraps, is a JPA or Camunda optimistic-locking conflict.
     */
    public static boolean isOptimisticLockFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof OptimisticLockingFailureException || t instanceof OptimisticLockException
                    || t instanceof org.camunda.bpm.engine.OptimisticLockingException) {
                return true;
            }
        }
        return false;
    }
    
    private static void pause(int attempt) {
        try {
            Thread.sleep(25L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    public <T> T call(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                if (!isOptimisticLockFailure(e) || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("Concurrent update detected (attempt {} of {}); retrying", attempt, MAX_ATTEMPTS);
                pause(attempt);
            }
        }
    }
    
    public void run(Runnable action) {
        call(() -> {
            action.run();
            return null;
        });
    }
}
