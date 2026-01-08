package com.enterprise.regulatory.worker;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.client.topic.TopicSubscription;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.repository.RegulatoryRequestRepository;
import com.enterprise.regulatory.service.AuditService;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * External Task Worker for handling task escalations on SLA breach.
 * Marks the request as escalated and records audit events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EscalationWorker implements ExternalTaskHandler {

    private static final String TOPIC_NAME = "escalation-handler";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_TIMEOUT = 5000L;

    private final ExternalTaskClient externalTaskClient;
    private final RegulatoryRequestRepository requestRepository;
    private final AuditService auditService;
    private TopicSubscription subscription;

    @EventListener(ApplicationReadyEvent.class)
    @Order(3)
    public void subscribe() {
        log.info("Subscribing to topic: {}", TOPIC_NAME);
        subscription = externalTaskClient.subscribe(TOPIC_NAME)
                .lockDuration(30000)
                .handler(this)
                .open();
    }

    @PreDestroy
    public void unsubscribe() {
        if (subscription != null) {
            subscription.close();
        }
    }

    @Override
    @Transactional
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        String processInstanceId = externalTask.getProcessInstanceId();
        String escalationTarget = externalTask.getVariable("escalationTarget");
        String originalTask = externalTask.getVariable("originalTask");
        String requestId = externalTask.getVariable("requestId");

        log.warn("SLA BREACH - Escalating task {} to {} for process: {}",
                originalTask, escalationTarget, processInstanceId);

        try {
            // Update the regulatory request with escalation info
            updateRequestEscalation(requestId, escalationTarget, originalTask);

            // Record audit events
            recordEscalationAuditEvents(processInstanceId, externalTask.getId(),
                    originalTask, escalationTarget);

            // Set output variables
            Map<String, Object> variables = new HashMap<>();
            variables.put("escalated", true);
            variables.put("escalationTarget", escalationTarget);
            variables.put("escalationTimestamp", System.currentTimeMillis());
            variables.put("escalationReason", "SLA breach on task: " + originalTask);
            variables.put("escalationProcessed", true);

            externalTaskService.complete(externalTask, variables);
            log.info("Escalation completed for process: {}, new target: {}", processInstanceId, escalationTarget);

        } catch (Exception e) {
            log.error("Error during escalation handling for process: {}", processInstanceId, e);
            handleFailure(externalTask, externalTaskService, e);
        }
    }

    private void updateRequestEscalation(String requestId, String escalationTarget, String originalTask) {
        if (requestId == null) {
            log.warn("No requestId provided for escalation update");
            return;
        }

        try {
            UUID uuid = UUID.fromString(requestId);
            requestRepository.findById(Objects.requireNonNull(uuid)).ifPresent(request -> {
                request.setEscalated(true);
                request.setEscalatedAt(LocalDateTime.now());
                request.setEscalationReason("SLA breach on " + originalTask + " - escalated to " + escalationTarget);
                requestRepository.save(request);
                log.debug("Updated regulatory request with escalation info");
            });
        } catch (IllegalArgumentException e) {
            log.warn("Could not parse requestId as UUID: {}", requestId);
        }
    }

    private void recordEscalationAuditEvents(String processInstanceId, String taskId,
            String originalTask, String escalationTarget) {
        // Record SLA breach event
        auditService.recordAuditEvent(
                processInstanceId,
                taskId,
                originalTask,
                AuditEventType.SLA_BREACH,
                null,
                "Escalated to " + escalationTarget,
                "system",
                null,
                buildEscalationComment(originalTask, escalationTarget));

        // Record task escalation event
        auditService.recordAuditEvent(
                processInstanceId,
                taskId,
                originalTask,
                AuditEventType.TASK_ESCALATED,
                null,
                escalationTarget,
                "system",
                null,
                "Task reassigned due to SLA breach");
    }

    private String buildEscalationComment(String originalTask, String escalationTarget) {
        return String.format(
                "SLA breach detected on task '%s'. Task has been escalated to %s for immediate attention. " +
                        "Original SLA was exceeded at %s.",
                originalTask,
                escalationTarget,
                LocalDateTime.now());
    }

    private void handleFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception e) {
        int retries = externalTask.getRetries() != null ? externalTask.getRetries() : MAX_RETRIES;

        if (retries > 0) {
            log.warn("Retrying escalation handling, remaining retries: {}", retries - 1);
            externalTaskService.handleFailure(
                    externalTask,
                    "Escalation handling failed: " + e.getMessage(),
                    e.getClass().getName(),
                    retries - 1,
                    RETRY_TIMEOUT);
        } else {
            // Complete with error flag to not block the workflow
            log.error("Max retries exceeded for escalation handling, completing with error");
            Map<String, Object> variables = new HashMap<>();
            variables.put("escalated", true);
            variables.put("escalationError", e.getMessage());
            variables.put("escalationProcessed", false);
            externalTaskService.complete(externalTask, variables);
        }
    }
}
