package com.enterprise.regulatory.worker;

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

import com.enterprise.regulatory.repository.RegulatoryRequestRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * External Task Worker for Risk Scoring.
 * Calculates risk score based on request attributes and historical data.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RiskScoringWorker implements ExternalTaskHandler {

    private static final String TOPIC_NAME = "risk-scoring";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_TIMEOUT = 5000L;

    private final ExternalTaskClient externalTaskClient;
    private final RegulatoryRequestRepository requestRepository;
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
        String requestId = externalTask.getVariable("requestId");
        String requestType = externalTask.getVariable("requestType");
        String department = externalTask.getVariable("department");

        log.info("Calculating risk score for request: {}, type: {}, department: {}",
                requestId, requestType, department);

        try {
            int riskScore = calculateRiskScore(requestId, requestType, department);

            // Update the regulatory request with the risk score
            updateRequestRiskScore(requestId, riskScore);

            Map<String, Object> variables = new HashMap<>();
            variables.put("riskScore", riskScore);
            variables.put("riskCategory", categorizeRisk(riskScore));
            variables.put("riskAssessmentTimestamp", System.currentTimeMillis());

            externalTaskService.complete(externalTask, variables);
            log.info("Risk scoring completed for request: {} with score: {}", requestId, riskScore);

        } catch (Exception e) {
            log.error("Error during risk scoring for request: {}", requestId, e);
            handleFailure(externalTask, externalTaskService, e);
        }
    }

    private int calculateRiskScore(String requestId, String requestType, String department) {
        int baseScore = 30;

        // Adjust based on request type
        baseScore += getRequestTypeRiskFactor(requestType);

        // Adjust based on department
        baseScore += getDepartmentRiskFactor(department);

        // Random factor to simulate complex risk calculation
        // In production, this would use ML models or rule engines
        baseScore += (int) (Math.random() * 20);

        return Math.min(100, Math.max(0, baseScore));
    }

    private int getRequestTypeRiskFactor(String requestType) {
        if (requestType == null)
            return 10;

        return switch (requestType.toUpperCase()) {
            case "FINANCIAL_PRODUCT" -> 25;
            case "REGULATORY_CHANGE" -> 30;
            case "COMPLIANCE_EXEMPTION" -> 35;
            case "NEW_MARKET_ENTRY" -> 20;
            case "PRODUCT_MODIFICATION" -> 15;
            case "OPERATIONAL_CHANGE" -> 10;
            default -> 5;
        };
    }

    private int getDepartmentRiskFactor(String department) {
        if (department == null)
            return 5;

        return switch (department.toUpperCase()) {
            case "TRADING" -> 20;
            case "INVESTMENT" -> 15;
            case "RISK" -> 10;
            case "COMPLIANCE" -> 5;
            case "OPERATIONS" -> 8;
            default -> 5;
        };
    }

    private String categorizeRisk(int riskScore) {
        if (riskScore >= 80)
            return "CRITICAL";
        if (riskScore >= 60)
            return "HIGH";
        if (riskScore >= 40)
            return "MEDIUM";
        if (riskScore >= 20)
            return "LOW";
        return "MINIMAL";
    }

    private void updateRequestRiskScore(String requestId, int riskScore) {
        try {
            UUID uuid = UUID.fromString(requestId);
            requestRepository.findById(Objects.requireNonNull(uuid)).ifPresent(request -> {
                request.setRiskScore(riskScore);
                requestRepository.save(request);
            });
        } catch (IllegalArgumentException e) {
            log.warn("Could not parse requestId as UUID: {}", requestId);
        }
    }

    private void handleFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception e) {
        int retries = externalTask.getRetries() != null ? externalTask.getRetries() : MAX_RETRIES;

        if (retries > 0) {
            log.warn("Retrying risk scoring, remaining retries: {}", retries - 1);
            externalTaskService.handleFailure(
                    externalTask,
                    "Risk scoring failed: " + e.getMessage(),
                    e.getClass().getName(),
                    retries - 1,
                    RETRY_TIMEOUT);
        } else {
            log.error("Max retries exceeded for risk scoring, creating incident");
            // Set default risk score and continue
            Map<String, Object> variables = new HashMap<>();
            variables.put("riskScore", 50);
            variables.put("riskCategory", "MEDIUM");
            variables.put("riskAssessmentError", e.getMessage());
            externalTaskService.complete(externalTask, variables);
        }
    }
}
