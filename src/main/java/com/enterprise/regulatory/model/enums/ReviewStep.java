package com.enterprise.regulatory.model.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The human steps of the approval process, keyed by their BPMN task id, with the process
 * variables each one writes and the decisions its outgoing gateway can route.
 *
 * <p>This is the single place that maps a user task to its variables; matching on task
 * names instead is fragile ("Manager" also matches "Senior Manager").
 */
public enum ReviewStep {
    
    INITIAL_REVIEW("Task_InitialReview", "reviewerDecision", "reviewerComment", "REVIEWER",
        List.of("APPROVED", "REJECTED", "NEEDS_INFO")),
    
    /**
     * The submitter answers a NEEDS_INFO request; the only decision is to submit the answer.
     */
    PROVIDE_INFORMATION("Task_RequestInfo", null, "additionalInfo", null,
        List.of("APPROVED")),
    MANAGER_APPROVAL("Task_ManagerApproval", "managerDecision", "managerComment", "MANAGER",
        List.of("APPROVED", "REJECTED", "ESCALATE")),
    SENIOR_MANAGER_REVIEW("Task_SeniorManagerReview", "seniorManagerDecision", "seniorManagerComment",
        "SENIOR_MANAGER", List.of("APPROVED", "REJECTED")),
    COMPLIANCE_REVIEW("Task_ComplianceManualReview", "manualComplianceDecision", "complianceReviewComment",
        "COMPLIANCE", List.of("PASS", "FAIL")),
    FINAL_APPROVAL("Task_FinalApproval", "finalDecision", "finalComment", "ADMIN",
        List.of("APPROVED", "REJECTED"));
    
    private final String taskDefinitionKey;
    private final String decisionVariable;
    private final String commentVariable;
    private final String role;
    private final List<String> decisions;
    
    ReviewStep(String taskDefinitionKey, String decisionVariable, String commentVariable, String role,
               List<String> decisions) {
        this.taskDefinitionKey = taskDefinitionKey;
        this.decisionVariable = decisionVariable;
        this.commentVariable = commentVariable;
        this.role = role;
        this.decisions = decisions;
    }
    
    public static Optional<ReviewStep> of(String taskDefinitionKey) {
        return Arrays.stream(values())
                   .filter(step -> step.taskDefinitionKey.equals(taskDefinitionKey))
                   .findFirst();
    }
    
    public String taskDefinitionKey() {
        return taskDefinitionKey;
    }
    
    /**
     * Process variable the gateway after this step routes on, or {@code null} if none.
     */
    public String decisionVariable() {
        return decisionVariable;
    }
    
    public String commentVariable() {
        return commentVariable;
    }
    
    /**
     * Role recorded in the audit trail for this step, or {@code null} for the submitter's step.
     */
    public String role() {
        return role;
    }
    
    /**
     * Decisions this step accepts: exactly those its outgoing gateway can route.
     */
    public List<String> decisions() {
        return decisions;
    }
    
    public boolean accepts(String decision) {
        return decisions.contains(decision);
    }
}
