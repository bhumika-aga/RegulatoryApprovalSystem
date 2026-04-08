package com.enterprise.regulatory.listener;

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;

import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.service.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Execution Listener for recording workflow start events.
 * Uses static injection pattern required for Camunda listener integration.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowStartListener implements ExecutionListener {

    private final AuditService auditService;
    private final ObjectMapper objectMapper;

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
            String additionalData = buildAdditionalData(requestType, requestTitle);

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
                    "Regulatory approval workflow initiated for: " + (requestTitle != null ? requestTitle : "N/A"),
                    null,
                    additionalData);
        } catch (Exception e) {
            log.error("Error recording workflow start audit event", e);
        }
    }

    private String buildAdditionalData(String requestType, String requestTitle) {
        try {
            Map<String, String> data = new HashMap<>();
            data.put("requestType", requestType != null ? requestType : "N/A");
            data.put("requestTitle", requestTitle != null ? requestTitle : "N/A");
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize additional data", e);
            return "{}";
        }
    }
}
