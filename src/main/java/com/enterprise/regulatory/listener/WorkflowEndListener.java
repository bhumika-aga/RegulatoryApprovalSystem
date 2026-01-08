package com.enterprise.regulatory.listener;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.service.AuditService;

import lombok.extern.slf4j.Slf4j;

/**
 * Execution Listener for recording workflow end events.
 * Uses static injection pattern required for Camunda listener integration.
 */
@Component
@Slf4j
public class WorkflowEndListener implements ExecutionListener {

    private static AuditService auditService;

    @Autowired
    public void setAuditService(AuditService service) {
        WorkflowEndListener.auditService = service;
    }

    @Override
    public void notify(DelegateExecution execution) {
        String processInstanceId = execution.getProcessInstanceId();
        String activityId = execution.getCurrentActivityId();
        String finalStatus = (String) execution.getVariable("finalStatus");

        log.info("Workflow ending - Process: {}, Activity: {}, Status: {}",
                processInstanceId, activityId, finalStatus);

        try {
            boolean isApproved = activityId != null && activityId.contains("Approved");
            String outcome = isApproved ? "APPROVED" : "REJECTED";

            if (finalStatus != null) {
                outcome = finalStatus;
            }

            auditService.recordAuditEvent(
                    processInstanceId,
                    null,
                    "Workflow End",
                    AuditEventType.WORKFLOW_COMPLETED,
                    null,
                    outcome,
                    "system",
                    null,
                    "Regulatory approval workflow completed with status: " + outcome);

            log.info("Workflow completed - Process: {}, Final Status: {}", processInstanceId, outcome);
        } catch (Exception e) {
            log.error("Error recording workflow end audit event", e);
        }
    }
}
