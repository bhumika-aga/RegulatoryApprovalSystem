package com.enterprise.regulatory.service;

import com.enterprise.regulatory.model.enums.ReviewStep;
import org.junit.jupiter.api.Test;

import static com.enterprise.regulatory.model.enums.ReviewStep.*;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewStepRoutingTest {
    
    @Test
    void looksStepsUpByTaskDefinitionKey() {
        assertThat(ReviewStep.of("Task_SeniorManagerReview")).contains(SENIOR_MANAGER_REVIEW);
        assertThat(ReviewStep.of("Task_ManagerApproval")).contains(MANAGER_APPROVAL);
        assertThat(ReviewStep.of("Task_RiskAssessment")).isEmpty();
    }
    
    @Test
    void acceptsOnlyDecisionsTheNextGatewayRoutes() {
        assertThat(INITIAL_REVIEW.accepts("NEEDS_INFO")).isTrue();
        assertThat(INITIAL_REVIEW.accepts("ESCALATE")).isFalse();
        assertThat(MANAGER_APPROVAL.accepts("ESCALATE")).isTrue();
        assertThat(SENIOR_MANAGER_REVIEW.accepts("ESCALATE")).isFalse();
        assertThat(COMPLIANCE_REVIEW.accepts("PASS")).isTrue();
        assertThat(COMPLIANCE_REVIEW.accepts("APPROVED")).isFalse();
    }
    
    @Test
    void nextStageFollowsTheDecision() {
        assertThat(WorkflowTaskService.nextStage(INITIAL_REVIEW, "APPROVED")).isEqualTo("MANAGER_APPROVAL");
        assertThat(WorkflowTaskService.nextStage(INITIAL_REVIEW, "NEEDS_INFO")).isEqualTo("ADDITIONAL_INFORMATION");
        assertThat(WorkflowTaskService.nextStage(INITIAL_REVIEW, "REJECTED")).isEqualTo("COMPLETED");
        assertThat(WorkflowTaskService.nextStage(PROVIDE_INFORMATION, "APPROVED")).isEqualTo("INITIAL_REVIEW");
        assertThat(WorkflowTaskService.nextStage(MANAGER_APPROVAL, "ESCALATE")).isEqualTo("SENIOR_MANAGER_REVIEW");
        assertThat(WorkflowTaskService.nextStage(SENIOR_MANAGER_REVIEW, "REJECTED")).isEqualTo("COMPLETED");
        assertThat(WorkflowTaskService.nextStage(COMPLIANCE_REVIEW, "FAIL")).isEqualTo("COMPLETED");
        assertThat(WorkflowTaskService.nextStage(COMPLIANCE_REVIEW, "PASS")).isEqualTo("FINAL_APPROVAL");
    }
}
