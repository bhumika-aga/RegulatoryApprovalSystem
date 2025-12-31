package com.enterprise.regulatory.listener;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.service.AuditService;

import lombok.extern.slf4j.Slf4j;

/**
 * Execution Listener for recording workflow start events.
 */
@Component
@Slf4j
public class WorkflowStartListener implements ExecutionListener {

    private static AuditService auditService;

    @Autowired
    public void setAuditService(AuditService service) {
        WorkflowStartListener.auditService = service;
    }

    @Override
    public void notify(DelegateExecution execution) {
        String processInstanceId = execution.getProcessInstanceId();
        String processDefinitionKey = execution.getProcessDefinitionId();
        String submitterId = (String) execution.getVariable("submitterId");
        String requestTitle = (String) execution.getVariable("requestTitle");
        String requestType = (String) execution.getVariable("requestType");

        log.info("Workflow started - Process: {}, Submitter: {}, Title: {}",
                processInstanceId, submitterId, requestTitle);

        try {
            String additionalData = String.format(
                    "{\"requestType\":\"%s\",\"requestTitle\":\"%s\"}",
                    requestType != null ? requestType : "N/A",
                    requestTitle != null ? requestTitle : "N/A");

            auditService.recordAuditEventWithDetails(
                    processInstanceId,
                    processDefinitionKey,
                    null,
                    "Workflow Start",
                    AuditEventType.WORKFLOW_STARTED,
                    null,
                    "Process started",
                    submitterId != null ? submitterId : "system",
                    null,
                    "Regulatory approval workflow initiated for: " + requestTitle,
                    null,
                    additionalData);
        } catch (Exception e) {
            log.error("Error recording workflow start audit event", e);
        }
    }
}
