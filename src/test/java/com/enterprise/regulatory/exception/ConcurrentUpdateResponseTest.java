package com.enterprise.regulatory.exception;

import org.camunda.bpm.engine.OptimisticLockingException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentUpdateResponseTest {
    
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/tasks/t1/complete");
    
    @Test
    void camundaConflictIs409() {
        assertThat(handler.handleConcurrentUpdate(new OptimisticLockingException("engine conflict"), request)
                       .getStatusCode().value()).isEqualTo(409);
    }
    
    @Test
    void jpaConflictIs409() {
        assertThat(handler.handleConcurrentUpdate(
            new ObjectOptimisticLockingFailureException("RegulatoryRequest", "id"), request)
                       .getStatusCode().value()).isEqualTo(409);
    }
}
