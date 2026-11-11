package com.enterprise.regulatory.listener;

import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.model.enums.ReviewStep;
import com.enterprise.regulatory.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Task Listener for recording audit events on task lifecycle events.
 * Captures task creation, assignment, and completion events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaskAuditListener implements TaskListener {
    
    private final AuditService auditService;
    
    @Override
    public void notify(DelegateTask delegateTask) {
        String eventName = delegateTask.getEventName();
        String taskId = delegateTask.getId();
        String taskName = delegateTask.getName();
        
        log.debug("Task event: {} for task: {} ({})", eventName, taskName, taskId);
        
        try {
            switch (eventName) {
                case EVENTNAME_CREATE -> handleTaskCreated(delegateTask);
                case EVENTNAME_ASSIGNMENT -> handleTaskAssigned(delegateTask);
                case EVENTNAME_COMPLETE -> handleTaskCompleted(delegateTask);
                default -> log.debug("Unhandled task event: {}", eventName);
            }
        } catch (Exception e) {
            log.error("Error recording audit event for task: {}", taskId, e);
        }
    }
    
    private void handleTaskCreated(DelegateTask delegateTask) {
        String processInstanceId = delegateTask.getProcessInstanceId();
        String taskId = delegateTask.getId();
        String taskName = delegateTask.getName();
        
        auditService.recordAuditEvent(
            processInstanceId,
            taskId,
            taskName,
            AuditEventType.TASK_CREATED,
            null,
            "Task created",
            "system",
            null,
            buildTaskCreatedComment(delegateTask));
        
        log.info("Audit recorded: Task created - {} ({})", taskName, taskId);
    }
    
    private void handleTaskAssigned(DelegateTask delegateTask) {
        String processInstanceId = delegateTask.getProcessInstanceId();
        String taskId = delegateTask.getId();
        String taskName = delegateTask.getName();
        String assignee = delegateTask.getAssignee();
        
        if (assignee == null || assignee.isEmpty()) {
            return;
        }
        
        auditService.recordAuditEvent(
            processInstanceId,
            taskId,
            taskName,
            AuditEventType.TASK_CLAIMED,
            null,
            assignee,
            assignee,
            extractRole(delegateTask),
            "Task claimed by " + assignee);
        
        log.info("Audit recorded: Task assigned - {} to {}", taskName, assignee);
    }
    
    private void handleTaskCompleted(DelegateTask delegateTask) {
        String processInstanceId = delegateTask.getProcessInstanceId();
        String taskId = delegateTask.getId();
        String taskName = delegateTask.getName();
        String assignee = delegateTask.getAssignee();
        
        String decision = extractDecision(delegateTask);
        String comment = extractComment(delegateTask);
        
        auditService.recordAuditEvent(
            processInstanceId,
            taskId,
            taskName,
            AuditEventType.TASK_COMPLETED,
            null,
            decision,
            assignee != null ? assignee : "system",
            extractRole(delegateTask),
            comment != null ? comment : "Task completed");
        
        // Record decision if present
        if (decision != null && !decision.isEmpty()) {
            auditService.recordAuditEvent(
                processInstanceId,
                taskId,
                taskName,
                AuditEventType.DECISION_MADE,
                null,
                decision,
                assignee != null ? assignee : "system",
                extractRole(delegateTask),
                "Decision made: " + decision);
        }
        
        log.info("Audit recorded: Task completed - {} by {} with decision: {}", taskName, assignee, decision);
    }
    
    private String buildTaskCreatedComment(DelegateTask delegateTask) {
        StringBuilder comment = new StringBuilder();
        comment.append("Task '").append(delegateTask.getName()).append("' created.");
        
        String candidateGroups = delegateTask.getCandidates().isEmpty() ? null
                                     : delegateTask.getCandidates().toString();
        if (candidateGroups != null) {
            comment.append(" Candidate groups: ").append(candidateGroups).append(".");
        }
        
        if (delegateTask.getDueDate() != null) {
            comment.append(" Due date: ").append(delegateTask.getDueDate()).append(".");
        }
        
        return comment.toString();
    }
    
    private Optional<ReviewStep> stepOf(DelegateTask delegateTask) {
        return ReviewStep.of(delegateTask.getTaskDefinitionKey());
    }
    
    private String extractDecision(DelegateTask delegateTask) {
        return stepOf(delegateTask)
                   .map(ReviewStep::decisionVariable)
                   .map(delegateTask::getVariable)
                   .map(Object::toString)
                   .orElse(null);
    }
    
    private String extractComment(DelegateTask delegateTask) {
        Object comment = delegateTask.getVariable("taskComment");
        if (comment == null) {
            comment = stepOf(delegateTask)
                          .map(ReviewStep::commentVariable)
                          .map(delegateTask::getVariable)
                          .orElse(null);
        }
        return comment != null ? comment.toString() : null;
    }
    
    private String extractRole(DelegateTask delegateTask) {
        return stepOf(delegateTask).map(ReviewStep::role).orElse(null);
    }
}
