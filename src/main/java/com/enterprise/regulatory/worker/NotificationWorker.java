package com.enterprise.regulatory.worker;

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.client.topic.TopicSubscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * External Task Worker for Notification Service.
 * Handles sending notifications for workflow events.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationWorker implements ExternalTaskHandler {

    private static final String TOPIC_NAME = "notification-service";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_TIMEOUT = 5000L;

    private final ExternalTaskClient externalTaskClient;
    private TopicSubscription subscription;

    @Value("${app.notification.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${app.notification.slack.enabled:false}")
    private boolean slackEnabled;

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
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        String processInstanceId = externalTask.getProcessInstanceId();
        String notificationType = externalTask.getVariable("notificationType");
        String stage = externalTask.getVariable("stage");
        String submitterId = externalTask.getVariable("submitterId");
        String requestTitle = externalTask.getVariable("requestTitle");

        log.info("Processing notification - type: {}, stage: {}, processInstance: {}",
                notificationType, stage, processInstanceId);

        try {
            NotificationResult result = sendNotification(
                    notificationType,
                    stage,
                    submitterId,
                    requestTitle,
                    processInstanceId);

            Map<String, Object> variables = new HashMap<>();
            variables.put("notificationSent", result.success());
            variables.put("notificationTimestamp", System.currentTimeMillis());
            variables.put("notificationChannel", result.channel());

            externalTaskService.complete(externalTask, variables);
            log.info("Notification sent successfully - type: {}, channel: {}", notificationType, result.channel());

        } catch (Exception e) {
            log.error("Error sending notification", e);
            handleFailure(externalTask, externalTaskService, e);
        }
    }

    private NotificationResult sendNotification(String type, String stage, String recipient,
            String requestTitle, String processInstanceId) {
        String subject = buildSubject(type, stage);
        String body = buildBody(type, stage, requestTitle, processInstanceId);
        String channel = "LOG"; // Default to logging

        log.info("===========================================");
        log.info("NOTIFICATION");
        log.info("===========================================");
        log.info("Type: {}", type);
        log.info("Stage: {}", stage);
        log.info("Recipient: {}", recipient);
        log.info("Subject: {}", subject);
        log.info("Body: {}", body);
        log.info("===========================================");

        if (emailEnabled) {
            // In production: send email via SMTP or email service
            log.info("Email notification would be sent to: {}", recipient);
            channel = "EMAIL";
        }

        if (slackEnabled) {
            // In production: send Slack message via webhook
            log.info("Slack notification would be sent");
            channel = emailEnabled ? "EMAIL,SLACK" : "SLACK";
        }

        return new NotificationResult(true, channel);
    }

    private String buildSubject(String type, String stage) {
        return switch (type) {
            case "APPROVED" -> "Regulatory Request Approved";
            case "REJECTED" -> "Regulatory Request Rejected";
            case "ESCALATION" -> "SLA Breach - Task Escalated: " + stage;
            case "TASK_ASSIGNED" -> "New Task Assigned: " + stage;
            default -> "Regulatory Workflow Update";
        };
    }

    private String buildBody(String type, String stage, String requestTitle, String processInstanceId) {
        StringBuilder body = new StringBuilder();
        body.append("Request: ").append(requestTitle != null ? requestTitle : "N/A").append("\n");
        body.append("Process ID: ").append(processInstanceId).append("\n\n");

        switch (type) {
            case "APPROVED" -> body.append("Your regulatory request has been approved and completed.");
            case "REJECTED" ->
                body.append("Your regulatory request has been rejected. Please review the comments for details.");
            case "ESCALATION" -> {
                body.append("An SLA breach has occurred at stage: ").append(stage).append("\n");
                body.append("The task has been escalated to the next level for immediate attention.");
            }
            case "TASK_ASSIGNED" -> body.append("A new task has been assigned to you at stage: ").append(stage);
            default -> body.append("There has been an update to your regulatory request.");
        }

        return body.toString();
    }

    private void handleFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception e) {
        int retries = externalTask.getRetries() != null ? externalTask.getRetries() : MAX_RETRIES;

        if (retries > 0) {
            log.warn("Retrying notification, remaining retries: {}", retries - 1);
            externalTaskService.handleFailure(
                    externalTask,
                    "Notification failed: " + e.getMessage(),
                    e.getClass().getName(),
                    retries - 1,
                    RETRY_TIMEOUT);
        } else {
            // For notifications, we complete even on failure to not block the workflow
            log.warn("Max retries exceeded for notification, completing with failure flag");
            Map<String, Object> variables = new HashMap<>();
            variables.put("notificationSent", false);
            variables.put("notificationError", e.getMessage());
            externalTaskService.complete(externalTask, variables);
        }
    }

    private record NotificationResult(boolean success, String channel) {
    }
}
