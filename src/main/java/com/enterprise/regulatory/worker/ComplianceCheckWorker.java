package com.enterprise.regulatory.worker;

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

import java.util.HashMap;
import java.util.Map;

/**
 * External Task Worker for Compliance Checks.
 * Performs regulatory compliance validation on submitted requests.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ComplianceCheckWorker implements ExternalTaskHandler {
    
    private static final String TOPIC_NAME = "compliance-check";
    private static final long RETRY_TIMEOUT = 10000L;
    
    private final ExternalTaskClient externalTaskClient;
    private final AuditService auditService;
    private final RequestUpdateService requestUpdateService;
    private TopicSubscription subscription;
    
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void subscribe() {
        log.info("Subscribing to topic: {}", TOPIC_NAME);
        subscription = externalTaskClient.subscribe(TOPIC_NAME)
                           .lockDuration(60000)
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
        String requestId = externalTask.getVariable("requestId");
        String requestType = externalTask.getVariable("requestType");
        Integer riskScore = externalTask.getVariable("riskScore");
        
        log.info("Processing compliance check for request: {}, type: {}, riskScore: {}",
            requestId, requestType, riskScore);
        
        try {
            ComplianceResult result = performComplianceCheck(requestId, requestType, riskScore);
            
            Map<String, Object> variables = new HashMap<>();
            variables.put("complianceResult", result.name());
            variables.put("complianceCheckTimestamp", System.currentTimeMillis());
            
            if (result == ComplianceResult.PASS) {
                variables.put("complianceComment", "Automated compliance check passed");
                auditService.recordAuditEvent(
                    processInstanceId,
                    externalTask.getId(),
                    "Compliance Check",
                    AuditEventType.COMPLIANCE_CHECK_PASSED,
                    null,
                    result.name(),
                    "system",
                    "COMPLIANCE",
                    "Automated compliance verification completed successfully");
            } else if (result == ComplianceResult.FAIL) {
                variables.put("complianceComment",
                    "Automated compliance check failed - regulatory requirements not met");
                auditService.recordAuditEvent(
                    processInstanceId,
                    externalTask.getId(),
                    "Compliance Check",
                    AuditEventType.COMPLIANCE_CHECK_FAILED,
                    null,
                    result.name(),
                    "system",
                    "COMPLIANCE",
                    "Automated compliance verification failed");
            } else {
                variables.put("complianceComment", "Requires manual compliance review");
            }
            
            String comment = (String) variables.get("complianceComment");
            requestUpdateService.update(requestId, request -> {
                request.setComplianceResult(result);
                request.setComplianceComment(comment);
                request.setCurrentStage(switch (result) {
                    case PASS -> "FINAL_APPROVAL";
                    case FAIL -> "COMPLETED";
                    case REQUIRES_ADDITIONAL_INFO -> "COMPLIANCE_REVIEW";
                });
            });
            
            externalTaskService.complete(externalTask, variables);
            log.info("Compliance check completed for request: {} with result: {}", requestId, result);
            
        } catch (NotFoundException e) {
            // The task's workflow ended meanwhile (e.g. it was terminated): nothing left to do.
            log.info("External task {} no longer exists; its workflow has ended", externalTask.getId());
        } catch (Exception e) {
            log.error("Error during compliance check for request: {}", requestId, e);
            handleFailure(externalTask, externalTaskService, e);
        }
    }
    
    private ComplianceResult performComplianceCheck(String requestId, String requestType, Integer riskScore) {
        // Simulate compliance check logic
        // In production, this would call external compliance systems
        
        if (riskScore == null) {
            riskScore = 50; // Default risk score
        }
        
        // High-risk requests need manual review
        if (riskScore > 80) {
            return ComplianceResult.REQUIRES_ADDITIONAL_INFO;
        }
        
        // Medium-risk requests based on type
        if (riskScore > 60) {
            if ("FINANCIAL_PRODUCT".equals(requestType) || "REGULATORY_CHANGE".equals(requestType)) {
                return ComplianceResult.REQUIRES_ADDITIONAL_INFO;
            }
        }
        
        // Low-risk requests auto-pass
        if (riskScore < 30) {
            return ComplianceResult.PASS;
        }
        
        // Standard compliance checks for medium-risk
        boolean documentsComplete = checkDocumentCompleteness(requestId);
        boolean regulatoryRequirementsMet = checkRegulatoryRequirements(requestType);
        
        if (documentsComplete && regulatoryRequirementsMet) {
            return ComplianceResult.PASS;
        } else if (!documentsComplete) {
            return ComplianceResult.REQUIRES_ADDITIONAL_INFO;
        } else {
            return ComplianceResult.FAIL;
        }
    }
    
    private boolean checkDocumentCompleteness(String requestId) {
        // Simulate document completeness check
        return requestId != null && !requestId.isEmpty();
    }
    
    private boolean checkRegulatoryRequirements(String requestType) {
        // Simulate regulatory requirement validation
        // In production, this would validate against specific regulatory rules
        return requestType != null && !requestType.equals("PROHIBITED");
    }
    
    private void handleFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception e) {
        // Critical: no fallback. After the last attempt (0 retries left) Camunda raises an
        // incident, which an operator can retry from Cockpit.
        int remaining = WorkerRetries.remainingAfterFailure(externalTask);
        if (remaining > 0) {
            log.warn("Retrying compliance check, remaining retries: {}", remaining);
        } else {
            log.error("Compliance check failed on the last attempt; raising an incident");
        }
        externalTaskService.handleFailure(
            externalTask,
            "Compliance check failed: " + e.getMessage(),
            e.getClass().getName(),
            remaining,
            RETRY_TIMEOUT);
    }
}
