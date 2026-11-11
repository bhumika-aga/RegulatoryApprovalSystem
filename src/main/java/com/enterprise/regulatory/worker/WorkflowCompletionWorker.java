package com.enterprise.regulatory.worker;

import com.enterprise.regulatory.model.entity.RegulatoryRequest;
import com.enterprise.regulatory.model.enums.ApprovalStatus;
import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.model.enums.ComplianceResult;
import com.enterprise.regulatory.service.AuditService;
import com.enterprise.regulatory.service.RequestUpdateService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.exception.NotFoundException;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.client.topic.TopicSubscription;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * External Task Worker for handling workflow completion.
 * Updates the regulatory request status and records final audit events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowCompletionWorker implements ExternalTaskHandler {
    
    private static final String TOPIC_NAME = "workflow-completion";
    private static final long RETRY_TIMEOUT = 5000L;
    
    private final ExternalTaskClient externalTaskClient;
    private final RequestUpdateService requestUpdateService;
    private final AuditService auditService;
    private TopicSubscription subscription;
    
    @EventListener(ApplicationReadyEvent.class)
    @Order(5)
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
        String outcome = externalTask.getVariable("outcome");
        String requestId = externalTask.getVariable("requestId");
        
        log.info("Completing workflow for process: {} with outcome: {}", processInstanceId, outcome);
        
        try {
            boolean isApproved = "APPROVED".equalsIgnoreCase(outcome);
            ApprovalStatus finalStatus = isApproved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;
            
            // Update the regulatory request
            updateRequestCompletion(requestId, finalStatus);
            
            // Record the final decision. WORKFLOW_COMPLETED is recorded once, by
            // WorkflowEndListener, when the process actually reaches its end event.
            recordFinalDecision(processInstanceId, externalTask.getId(), outcome);
            
            // Set output variables
            Map<String, Object> variables = new HashMap<>();
            variables.put("workflowCompleted", true);
            variables.put("completionTimestamp", System.currentTimeMillis());
            variables.put("finalStatus", finalStatus.name());
            
            externalTaskService.complete(externalTask, variables);
            log.info("Workflow completion recorded for process: {}, status: {}", processInstanceId, finalStatus);
            
        } catch (NotFoundException e) {
            // The task's workflow ended meanwhile (e.g. it was terminated): nothing left to do.
            log.info("External task {} no longer exists; its workflow has ended", externalTask.getId());
        } catch (Exception e) {
            log.error("Error during workflow completion for process: {}", processInstanceId, e);
            handleFailure(externalTask, externalTaskService, e);
        }
    }
    
    private void updateRequestCompletion(String requestId, ApprovalStatus status) {
        requestUpdateService.update(requestId, request -> {
            // The overall outcome is captured by the status; the stage-specific
            // decision fields (e.g. finalDecision) are owned by the user tasks and
            // must not be overwritten here, otherwise the rejection reason would
            // misattribute the rejection to the Final Approval stage.
            if (status == ApprovalStatus.REJECTED && request.getRejectionReason() == null) {
                request.setRejectionReason(buildRejectionReason(request));
            }
            
            request.setStatus(status);
            request.setCompletedAt(LocalDateTime.now());
            request.setCurrentStage("COMPLETED");
            request.setCurrentAssignee(null);
            log.debug("Updated regulatory request with completion status: {}", status);
        });
    }
    
    private void recordFinalDecision(String processInstanceId, String taskId, String outcome) {
        auditService.recordAuditEvent(
            processInstanceId,
            taskId,
            "Final Decision",
            AuditEventType.DECISION_MADE,
            null,
            outcome,
            "system",
            null,
            "Final workflow outcome: " + outcome);
    }
    
    private String buildRejectionReason(RegulatoryRequest request) {
        StringBuilder reason = new StringBuilder("Request rejected during workflow processing. ");
        
        if (request.getReviewerDecision() != null && "REJECTED".equals(request.getReviewerDecision())) {
            reason.append("Rejected at Initial Review stage. ");
            if (request.getReviewerComment() != null) {
                reason.append("Reviewer comment: ").append(request.getReviewerComment()).append(". ");
            }
        }
        
        if (request.getManagerDecision() != null && "REJECTED".equals(request.getManagerDecision())) {
            reason.append("Rejected at Manager Approval stage. ");
            if (request.getManagerComment() != null) {
                reason.append("Manager comment: ").append(request.getManagerComment()).append(". ");
            }
        }
        
        if (request.getSeniorManagerDecision() != null && "REJECTED".equals(request.getSeniorManagerDecision())) {
            reason.append("Rejected at Senior Manager Review stage. ");
            if (request.getSeniorManagerComment() != null) {
                reason.append("Senior manager comment: ").append(request.getSeniorManagerComment()).append(". ");
            }
        }
        
        if (request.getComplianceResult() == ComplianceResult.FAIL) {
            reason.append("Failed compliance check. ");
            if (request.getComplianceComment() != null) {
                reason.append("Compliance comment: ").append(request.getComplianceComment()).append(". ");
            }
        }
        
        if (request.getFinalDecision() != null && "REJECTED".equals(request.getFinalDecision())) {
            reason.append("Rejected at Final Approval stage. ");
            if (request.getFinalComment() != null) {
                reason.append("Final comment: ").append(request.getFinalComment()).append(". ");
            }
        }
        
        return reason.toString().trim();
    }
    
    private void handleFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception e) {
        // Critical: no fallback. After the last attempt (0 retries left) Camunda raises an
        // incident, which an operator can retry from Cockpit.
        int remaining = WorkerRetries.remainingAfterFailure(externalTask);
        if (remaining > 0) {
            log.warn("Retrying workflow completion, remaining retries: {}", remaining);
        } else {
            log.error("Workflow completion failed on the last attempt; raising an incident");
        }
        externalTaskService.handleFailure(
            externalTask,
            "Workflow completion failed: " + e.getMessage(),
            e.getClass().getName(),
            remaining,
            RETRY_TIMEOUT);
    }
}
