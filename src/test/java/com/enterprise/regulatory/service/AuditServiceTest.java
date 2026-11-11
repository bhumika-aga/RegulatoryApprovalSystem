package com.enterprise.regulatory.service;

import com.enterprise.regulatory.model.entity.WorkflowAudit;
import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.repository.WorkflowAuditRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Audit records are written only once the caller's transaction commits.
 */
class AuditServiceTest {
    
    private final WorkflowAuditRepository repository = mock(WorkflowAuditRepository.class);
    private final AuditService auditService =
        new AuditService(repository, Runnable::run, mock(PlatformTransactionManager.class));
    
    private void record() {
        auditService.recordAuditEvent("pid", "tid", "Initial Review", AuditEventType.TASK_COMPLETED,
            null, "APPROVED", "reviewer", "REVIEWER", "ok");
    }
    
    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
    
    @Test
    void writesImmediatelyOutsideATransaction() {
        record();
        verify(repository).save(any(WorkflowAudit.class));
    }
    
    @Test
    void waitsForTheTransactionToCommit() {
        TransactionSynchronizationManager.initSynchronization();
        record();
        verify(repository, never()).save(any());
        
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(repository).save(any(WorkflowAudit.class));
    }
    
    @Test
    void writesNothingWhenTheTransactionRollsBack() {
        TransactionSynchronizationManager.initSynchronization();
        record();
        TransactionSynchronizationManager.getSynchronizations()
            .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(repository, never()).save(any());
    }
}
