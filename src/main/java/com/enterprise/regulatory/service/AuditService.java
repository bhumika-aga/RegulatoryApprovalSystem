package com.enterprise.regulatory.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.regulatory.model.entity.WorkflowAudit;
import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.repository.WorkflowAuditRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing audit events in the workflow system.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final WorkflowAuditRepository auditRepository;

    @Async
    @Transactional
    public void recordAuditEvent(String processInstanceId, String taskId, String taskName,
            AuditEventType eventType, String oldValue, String newValue,
            String performedBy, String role, String comment) {
        try {
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

            auditRepository.save(audit);
            log.debug("Audit event recorded: {} for process: {}", eventType, processInstanceId);
        } catch (Exception e) {
            log.error("Failed to record audit event: {} for process: {}", eventType, processInstanceId, e);
        }
    }

    @Async
    @Transactional
    public void recordAuditEventWithDetails(String processInstanceId, String processDefinitionKey,
            String taskId, String taskName, AuditEventType eventType,
            String oldValue, String newValue, String performedBy,
            String role, String comment, String ipAddress,
            String additionalData) {
        try {
            WorkflowAudit audit = Objects.requireNonNull(WorkflowAudit.builder()
                    .processInstanceId(processInstanceId)
                    .processDefinitionKey(processDefinitionKey)
                    .taskId(taskId)
                    .taskName(taskName)
                    .eventType(eventType)
                    .oldValue(oldValue)
                    .newValue(newValue)
                    .performedBy(performedBy)
                    .role(role)
                    .comment(comment)
                    .timestamp(LocalDateTime.now())
                    .ipAddress(ipAddress)
                    .additionalData(additionalData)
                    .build());

            auditRepository.save(audit);
            log.debug("Detailed audit event recorded: {} for process: {}", eventType, processInstanceId);
        } catch (Exception e) {
            log.error("Failed to record detailed audit event: {} for process: {}", eventType, processInstanceId, e);
        }
    }

    @Transactional(readOnly = true)
    public List<WorkflowAudit> getAuditTrailForProcess(String processInstanceId) {
        return auditRepository.findByProcessInstanceIdOrderByTimestampDesc(processInstanceId);
    }

    @Transactional(readOnly = true)
    public Page<WorkflowAudit> getAuditTrailForProcess(String processInstanceId, Pageable pageable) {
        return auditRepository.findByProcessInstanceId(processInstanceId, pageable);
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
    public Page<WorkflowAudit> getAuditTrailByUser(String userId, Pageable pageable) {
        return auditRepository.findByPerformedBy(userId, pageable);
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
