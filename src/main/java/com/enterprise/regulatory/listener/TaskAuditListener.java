package com.enterprise.regulatory.listener;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.service.AuditService;

import lombok.extern.slf4j.Slf4j;

/**
 * Task Listener for recording audit events on task lifecycle events.
 * Captures task creation, assignment, and completion events.
 */
@Component
@Slf4j
public class TaskAuditListener implements TaskListener {

    private static AuditService auditService;

    @Autowired
    public void setAuditService(AuditService service) {
        TaskAuditListener.auditService = service;
    }

    @Override
    public void notify(DelegateTask delegateTask) {
        String eventName = delegateTask.getEventName();
        String taskId = delegateTask.getId();
        String taskName = delegateTask.getName();
        String processInstanceId = delegateTask.getProcessInstanceId();
        String assignee = delegateTask.getAssignee();

        log.debug("Task event: {} for task: {} ({})", eventName, taskName, taskId);

        try {
            switch (eventName) {
                case EVENTNAME_CREATE -> handleTaskCreated(delegateTask);
                case EVENTNAME_ASSIGNMENT -> handleTaskAssigned(delegateTask);
                case EVENTNAME_COMPLETE -> handleTaskCompleted(delegateTask);
                case EVENTNAME_DELETE -> handleTaskDeleted(delegateTask);
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

    private void handleTaskDeleted(DelegateTask delegateTask) {
        String processInstanceId = delegateTask.getProcessInstanceId();
        String taskId = delegateTask.getId();
        String taskName = delegateTask.getName();

        log.debug("Task deleted: {} ({})", taskName, taskId);
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

    private String extractDecision(DelegateTask delegateTask) {
        String taskName = delegateTask.getName();

        // Try to get the appropriate decision variable based on task
        Object decision = null;
        if (taskName.contains("Initial Review")) {
            decision = delegateTask.getVariable("reviewerDecision");
        } else if (taskName.contains("Manager")) {
            decision = delegateTask.getVariable("managerDecision");
        } else if (taskName.contains("Senior Manager")) {
            decision = delegateTask.getVariable("seniorManagerDecision");
        } else if (taskName.contains("Compliance")) {
            decision = delegateTask.getVariable("manualComplianceDecision");
        } else if (taskName.contains("Final")) {
            decision = delegateTask.getVariable("finalDecision");
        }

        return decision != null ? decision.toString() : null;
    }

    private String extractComment(DelegateTask delegateTask) {
        Object comment = delegateTask.getVariable("taskComment");
        if (comment != null) {
            return comment.toString();
        }

        // Try task-specific comments
        String taskName = delegateTask.getName();
        if (taskName.contains("Initial Review")) {
            comment = delegateTask.getVariable("reviewerComment");
        } else if (taskName.contains("Manager")) {
            comment = delegateTask.getVariable("managerComment");
        } else if (taskName.contains("Compliance")) {
            comment = delegateTask.getVariable("complianceComment");
        } else if (taskName.contains("Final")) {
            comment = delegateTask.getVariable("finalComment");
        }

        return comment != null ? comment.toString() : null;
    }

    private String extractRole(DelegateTask delegateTask) {
        String taskName = delegateTask.getName();

        if (taskName.contains("Initial Review"))
            return "REVIEWER";
        if (taskName.contains("Senior Manager"))
            return "SENIOR_MANAGER";
        if (taskName.contains("Manager"))
            return "MANAGER";
        if (taskName.contains("Compliance"))
            return "COMPLIANCE";
        if (taskName.contains("Final"))
            return "ADMIN";

        return null;
    }
}
