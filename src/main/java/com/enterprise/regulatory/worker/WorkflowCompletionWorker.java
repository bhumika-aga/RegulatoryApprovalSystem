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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.regulatory.model.entity.RegulatoryRequest;
import com.enterprise.regulatory.model.enums.ApprovalStatus;
import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.repository.RegulatoryRequestRepository;
import com.enterprise.regulatory.service.AuditService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * External Task Worker for handling workflow completion.
 * Updates the regulatory request status and records final audit events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowCompletionWorker implements ExternalTaskHandler {

    private static final String TOPIC_NAME = "workflow-completion";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_TIMEOUT = 5000L;

    private final ExternalTaskClient externalTaskClient;
    private final RegulatoryRequestRepository requestRepository;
    private final AuditService auditService;
    private TopicSubscription subscription;

    @PostConstruct
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
        String outcome = externalTask.getVariable("outcome");
        String requestId = externalTask.getVariable("requestId");

        log.info("Completing workflow for process: {} with outcome: {}", processInstanceId, outcome);

        try {
            boolean isApproved = "APPROVED".equalsIgnoreCase(outcome);
            ApprovalStatus finalStatus = isApproved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED;

            // Update the regulatory request
            updateRequestCompletion(requestId, finalStatus, outcome);

            // Record audit events
            recordCompletionAuditEvents(processInstanceId, externalTask.getId(),
                    isApproved, finalStatus, outcome);

            // Set output variables
            Map<String, Object> variables = new HashMap<>();
            variables.put("workflowCompleted", true);
            variables.put("completionTimestamp", System.currentTimeMillis());
            variables.put("finalStatus", finalStatus.name());

            externalTaskService.complete(externalTask, variables);
            log.info("Workflow completion recorded for process: {}, status: {}", processInstanceId, finalStatus);

        } catch (Exception e) {
            log.error("Error during workflow completion for process: {}", processInstanceId, e);
            handleFailure(externalTask, externalTaskService, e);
        }
    }

    private void updateRequestCompletion(String requestId, ApprovalStatus status, String outcome) {
        if (requestId == null) {
            log.warn("No requestId provided for completion update");
            return;
        }

        try {
            UUID uuid = UUID.fromString(requestId);
            requestRepository.findById(Objects.requireNonNull(uuid)).ifPresent(request -> {
                request.setStatus(status);
                request.setFinalDecision(outcome);
                request.setCompletedAt(LocalDateTime.now());
                request.setCurrentStage("COMPLETED");
                request.setCurrentAssignee(null);

                if (status == ApprovalStatus.REJECTED && request.getRejectionReason() == null) {
                    request.setRejectionReason(buildRejectionReason(request));
                }

                requestRepository.save(request);
                log.debug("Updated regulatory request with completion status: {}", status);
            });
        } catch (IllegalArgumentException e) {
            log.warn("Could not parse requestId as UUID: {}", requestId);
        }
    }

    private void recordCompletionAuditEvents(String processInstanceId, String taskId,
            boolean isApproved, ApprovalStatus finalStatus, String outcome) {
        String comment = isApproved
                ? "Regulatory request approved and workflow completed successfully"
                : "Regulatory request rejected. See rejection reason for details.";

        // Record workflow completion event
        auditService.recordAuditEvent(
                processInstanceId,
                taskId,
                "Workflow Completion",
                AuditEventType.WORKFLOW_COMPLETED,
                null,
                finalStatus.name(),
                "system",
                null,
                comment);

        // Record final decision event
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

        if (request.getComplianceResult() != null && "FAIL".equals(request.getComplianceResult().name())) {
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
        int retries = externalTask.getRetries() != null ? externalTask.getRetries() : MAX_RETRIES;

        if (retries > 0) {
            log.warn("Retrying workflow completion, remaining retries: {}", retries - 1);
            externalTaskService.handleFailure(
                    externalTask,
                    "Workflow completion failed: " + e.getMessage(),
                    e.getClass().getName(),
                    retries - 1,
                    RETRY_TIMEOUT);
        } else {
            // Complete with error flag - this is critical, so we report failure
            log.error("Max retries exceeded for workflow completion, creating incident");
            externalTaskService.handleBpmnError(
                    externalTask,
                    "COMPLETION_ERROR",
                    "Workflow completion failed after max retries: " + e.getMessage());
        }
    }
}
