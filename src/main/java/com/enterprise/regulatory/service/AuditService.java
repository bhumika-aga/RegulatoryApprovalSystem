package com.enterprise.regulatory.service;

import com.enterprise.regulatory.model.entity.WorkflowAudit;
import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.repository.WorkflowAuditRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Service for managing audit events in the workflow system.
 *
 * <p>Events are written asynchronously, so auditing never slows the request path, and only
 * once the caller's transaction has committed: an action that is rolled back (or retried
 * after a concurrent update) leaves no record of something that did not happen.
 */
@Service
@Slf4j
public class AuditService {
    
    private final WorkflowAuditRepository auditRepository;
    private final Executor auditExecutor;
    /**
     * Each write gets its own transaction, also when the executor runs it on the caller's thread
     * (pool saturated) inside {@code afterCommit}, where it must not join the finished one.
     */
    private final TransactionTemplate writeTransaction;
    
    public AuditService(WorkflowAuditRepository auditRepository, @Qualifier("taskExecutor") Executor auditExecutor,
                        PlatformTransactionManager transactionManager) {
        this.auditRepository = auditRepository;
        this.auditExecutor = auditExecutor;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.writeTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    
    public void recordAuditEvent(String processInstanceId, String taskId, String taskName,
                                 AuditEventType eventType, String oldValue, String newValue,
                                 String performedBy, String role, String comment) {
        WorkflowAudit audit = Objects.requireNonNull(WorkflowAudit.builder()
                                                         .processInstanceId(processInstanceId)
                                                         .taskId(taskId)
                                                         .taskName(taskName)
                                                         .eventType(eventType)
                                                         .oldValue(oldValue)
                                                         .newValue(newValue)
                                                         .performedBy(performedBy)
                                                         .role(role)
                                                         .comment(comment)
                                                         .timestamp(LocalDateTime.now())
                                                         .build());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(audit);
                }
            });
        } else {
            write(audit);
        }
    }
    
    private void write(WorkflowAudit audit) {
        auditExecutor.execute(() -> {
            try {
                writeTransaction.executeWithoutResult(status -> auditRepository.save(audit));
                log.debug("Audit event recorded: {} for process: {}", audit.getEventType(), audit.getProcessInstanceId());
            } catch (Exception e) {
                log.error("Failed to record audit event: {} for process: {}",
                    audit.getEventType(), audit.getProcessInstanceId(), e);
            }
        });
    }
    
    @Transactional(readOnly = true)
    public List<WorkflowAudit> getAuditTrailForProcess(String processInstanceId) {
        return auditRepository.findByProcessInstanceIdOrderByTimestampDesc(processInstanceId);
    }
    
    @Transactional(readOnly = true)
    public List<WorkflowAudit> getAuditTrailForTask(String taskId) {
        return auditRepository.findByTaskIdOrderByTimestampDesc(taskId);
    }
    
    @Transactional(readOnly = true)
    public List<WorkflowAudit> getAuditTrailByUser(String userId) {
        return auditRepository.findByPerformedByOrderByTimestampDesc(userId);
    }
    
    @Transactional(readOnly = true)
    public List<WorkflowAudit> getAuditByEventType(AuditEventType eventType) {
        return auditRepository.findByEventTypeOrderByTimestampDesc(eventType);
    }
    
    @Transactional(readOnly = true)
    public List<WorkflowAudit> getAuditByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return auditRepository.findByDateRange(startDate, endDate);
    }
    
    @Transactional(readOnly = true)
    public List<String> getProcessInstancesWithSlaBreaches(LocalDateTime since) {
        return auditRepository.findProcessInstancesWithSlaBreaches(since);
    }
    
    @Transactional(readOnly = true)
    public long countEventsSince(AuditEventType eventType, LocalDateTime since) {
        return auditRepository.countEventsSince(eventType, since);
    }
}
