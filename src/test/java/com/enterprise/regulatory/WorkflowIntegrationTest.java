package com.enterprise.regulatory;

import com.fasterxml.jackson.databind.JsonNode;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.runtime.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The approval process itself: decision validation, request stages, the audit trail,
 * compliance results and SLA escalation.
 */
class WorkflowIntegrationTest extends IntegrationTestBase {
    
    @Autowired
    private ManagementService managementService;
    
    @Test
    void refusesADecisionTheTaskCannotRoute() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String pid = startWorkflow(admin, "Unroutable decision");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        
        ResponseEntity<String> res = call(HttpMethod.POST, "/api/v1/tasks/" + initialReview + "/complete", reviewer,
            Map.of("decision", "ESCALATE"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).contains("APPROVED", "REJECTED", "NEEDS_INFO");
        // The task is still open and can be completed with a valid decision.
        assertThat(complete(reviewer, initialReview).getStatusCode().value()).isEqualTo(200);
    }
    
    @Test
    void stageIsRiskAssessmentUntilTheScoreIsStored() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String pid = startWorkflow(admin, "Stage during risk scoring");
        assertThat(json(call(HttpMethod.GET, "/api/v1/workflow/status/" + pid, admin, null)).at("/data/currentStage")
                       .asText()).isIn("RISK_ASSESSMENT", "INITIAL_REVIEW");
        awaitTask(reviewer, pid, "Task_InitialReview");
        JsonNode workflow = awaitWorkflow(admin, pid, w -> w.hasNonNull("riskScore"));
        assertThat(workflow.path("currentStage").asText()).isEqualTo("INITIAL_REVIEW");
    }
    
    @Test
    void stageFollowsTheDecisionAndCompletionIsAuditedOnce() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String auditor = login("auditor");
        String pid = startWorkflow(admin, "Rejected at initial review");
        
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + awaitTask(reviewer, pid, "Task_InitialReview")
                                             + "/complete", reviewer, Map.of("decision", "REJECTED"))
                       .getStatusCode().value()).isEqualTo(200);
        
        JsonNode status = awaitWorkflow(admin, pid, w -> "REJECTED".equals(w.path("status").asText()));
        assertThat(status.path("currentStage").asText()).isEqualTo("COMPLETED");
        assertThat(status.path("rejectionReason").asText()).contains("Initial Review");
        
        awaitAuditEvent(auditor, pid, "WORKFLOW_COMPLETED", null);
        sleep(1000); // let any late asynchronous audit writes land
        assertThat(auditEvents(auditor, pid, "WORKFLOW_COMPLETED", null)).hasSize(1);
    }
    
    @Test
    void seniorManagerDecisionIsAuditedAndComplianceResultRecorded() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String manager = login("manager");
        String seniorManager = login("senior.manager");
        String auditor = login("auditor");
        String pid = startWorkflow(admin, "Escalated to senior manager");
        
        complete(reviewer, awaitTask(reviewer, pid, "Task_InitialReview"));
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + awaitTask(manager, pid, "Task_ManagerApproval")
                                             + "/complete", manager, Map.of("decision", "ESCALATE"))
                       .getStatusCode().value()).isEqualTo(200);
        assertThat(awaitWorkflow(admin, pid, w -> true).path("currentStage").asText())
            .isEqualTo("SENIOR_MANAGER_REVIEW");
        complete(seniorManager, awaitTask(seniorManager, pid, "Task_SeniorManagerReview"));
        
        // The audit shows the senior manager's own decision, not the manager's ESCALATE.
        JsonNode decision = awaitAuditEvent(auditor, pid, "DECISION_MADE", "Senior Manager Review");
        assertThat(decision.path("newValue").asText()).isEqualTo("APPROVED");
        assertThat(decision.path("role").asText()).isEqualTo("SENIOR_MANAGER");
        
        // The automated compliance check records its result on the request.
        JsonNode workflow = awaitWorkflow(admin, pid, w -> !w.path("complianceResult").isMissingNode()
                                                               && !w.path("complianceResult").isNull());
        assertThat(workflow.path("complianceResult").asText()).isIn("PASS", "FAIL", "REQUIRES_ADDITIONAL_INFO");
    }
    
    @Test
    void slaEscalationHandsTheTaskToTheEscalationTarget() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String manager = login("manager");
        String pid = startWorkflow(admin, "SLA breach");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        // The reviewer claims it, then sits on it past the 8-hour SLA.
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + initialReview + "/claim", reviewer, null)
                       .getStatusCode().value()).isEqualTo(200);
        
        Job slaTimer = managementService.createJobQuery().processInstanceId(pid).timers().singleResult();
        managementService.executeJob(slaTimer.getId());
        
        // Escalated to MANAGER: the claim is released and a manager can now complete it.
        assertThat(awaitTask(manager, pid, "Task_InitialReview")).isEqualTo(initialReview);
        assertThat(complete(manager, initialReview).getStatusCode().value()).isEqualTo(200);
        assertThat(awaitWorkflow(admin, pid, w -> w.path("escalated").asBoolean()).path("escalationReason").asText())
            .contains("MANAGER");
    }
}
