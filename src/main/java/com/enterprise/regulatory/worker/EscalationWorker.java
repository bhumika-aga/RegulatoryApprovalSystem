package com.enterprise.regulatory.worker;

import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.service.RequestUpdateService;
import com.enterprise.regulatory.service.AuditService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.exception.NotFoundException;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.client.topic.TopicSubscription;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.Task;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * External Task Worker for handling task escalations on SLA breach.
 *
 * <p>Reassigns the overdue user task ({@code escalatedTaskKey}) to the escalation target: the
 * target role becomes a candidate group and any current claim is released, so the target can
 * claim and complete it (the original group still can too). Also marks the request as
 * escalated and records audit events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EscalationWorker implements ExternalTaskHandler {
    
    private static final String TOPIC_NAME = "escalation-handler";
    private static final long RETRY_TIMEOUT = 5000L;
    
    private final ExternalTaskClient externalTaskClient;
    private final RequestUpdateService requestUpdateService;
    private final AuditService auditService;
    private final TaskService taskService;
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
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        String processInstanceId = externalTask.getProcessInstanceId();
        String escalationTarget = externalTask.getVariable("escalationTarget");
        String originalTask = externalTask.getVariable("originalTask");
        String requestId = externalTask.getVariable("requestId");
        String escalatedTaskKey = externalTask.getVariable("escalatedTaskKey");
        
        log.warn("SLA BREACH - Escalating task {} to {} for process: {}",
            originalTask, escalationTarget, processInstanceId);
        
        try {
            if (!reassign(processInstanceId, escalatedTaskKey, escalationTarget)) {
                log.info("Task {} of process {} is no longer open; nothing to escalate",
                    escalatedTaskKey, processInstanceId);
            }
            
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
            
        } catch (NotFoundException e) {
            // The task's workflow ended meanwhile (e.g. it was terminated): nothing left to do.
            log.info("External task {} no longer exists; its workflow has ended", externalTask.getId());
        } catch (Exception e) {
            log.error("Error during escalation handling for process: {}", processInstanceId, e);
            handleFailure(externalTask, externalTaskService, e);
        }
    }
    
    /**
     * Makes the escalation target a candidate for the open task and releases any claim on it.
     *
     * @return {@code false} if the task has already been completed
     */
    private boolean reassign(String processInstanceId, String taskKey, String escalationTarget) {
        Task task = taskService.createTaskQuery()
                        .processInstanceId(processInstanceId)
                        .taskDefinitionKey(taskKey)
                        .active()
                        .singleResult();
        if (task == null) {
            return false;
        }
        boolean alreadyCandidate = taskService.getIdentityLinksForTask(task.getId()).stream()
                                       .anyMatch(link -> "candidate".equals(link.getType())
                                                             && escalationTarget.equals(link.getGroupId()));
        if (!alreadyCandidate) {
            taskService.addCandidateGroup(task.getId(), escalationTarget);
        }
        if (task.getAssignee() != null) {
            taskService.setAssignee(task.getId(), null);
        }
        log.info("Escalated task {} ({}) to {}", task.getName(), task.getId(), escalationTarget);
        return true;
    }
    
    private void updateRequestEscalation(String requestId, String escalationTarget, String originalTask) {
        requestUpdateService.update(requestId, request -> {
            request.setEscalated(true);
            request.setEscalatedAt(LocalDateTime.now());
            request.setEscalationReason("SLA breach on " + originalTask + " - escalated to " + escalationTarget);
            request.setCurrentAssignee(null);
        });
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
        int remaining = WorkerRetries.remainingAfterFailure(externalTask);
        
        if (remaining > 0) {
            log.warn("Retrying escalation handling, remaining retries: {}", remaining);
            externalTaskService.handleFailure(
                externalTask,
                "Escalation handling failed: " + e.getMessage(),
                e.getClass().getName(),
                remaining,
                RETRY_TIMEOUT);
        } else {
            // Best-effort: complete with an error flag so a failed escalation never blocks the workflow
            log.error("Escalation handling failed on the last attempt, completing with error");
            Map<String, Object> variables = new HashMap<>();
            variables.put("escalated", true);
            variables.put("escalationError", e.getMessage());
            variables.put("escalationProcessed", false);
            externalTaskService.complete(externalTask, variables);
        }
    }
}
