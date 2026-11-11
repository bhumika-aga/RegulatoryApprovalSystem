package com.enterprise.regulatory.listener;

import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Execution Listener for recording workflow start events.
 * A Spring bean, referenced from the BPMN as a delegate expression.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowStartListener implements ExecutionListener {
    
    private final AuditService auditService;
    
    @Override
    public void notify(DelegateExecution execution) {
        String processInstanceId = execution.getProcessInstanceId();
        String submitterId = (String) execution.getVariable("submitterId");
        String requestTitle = (String) execution.getVariable("requestTitle");
        
        log.info("Workflow started - Process: {}, Submitter: {}, Title: {}",
            processInstanceId, submitterId, requestTitle);
        
        try {
            auditService.recordAuditEvent(
                processInstanceId,
                null,
                "Workflow Start",
                AuditEventType.WORKFLOW_STARTED,
                null,
                "Process started",
                submitterId != null ? submitterId : "system",
                null,
                "Regulatory approval workflow initiated for: " + (requestTitle != null ? requestTitle : "N/A"));
        } catch (Exception e) {
            log.error("Error recording workflow start audit event", e);
        }
    }
}
