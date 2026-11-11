package com.enterprise.regulatory.worker;

import org.camunda.bpm.client.task.ExternalTask;

/**
 * Retry accounting shared by the external task workers.
 *
 * <p>Camunda reports {@code null} retries on a task's first attempt and stops handing out a
 * task once its retries reach 0 (it raises an incident instead). So a worker must decide on
 * its last attempt, when the retries left after this failure are 0, whether to fall back
 * or to let the incident happen; checking for 0 retries on a later attempt never fires.
 */
final class WorkerRetries {
    
    /**
     * Attempts per task, including the first.
     */
    static final int MAX_ATTEMPTS = 3;
    
    private WorkerRetries() {
    }
    
    /**
     * Retries left once this attempt has failed: 0 means this was the last attempt.
     */
    static int remainingAfterFailure(ExternalTask task) {
        Integer retries = task.getRetries();
        return Math.max(0, (retries == null ? MAX_ATTEMPTS : retries) - 1);
    }
}
