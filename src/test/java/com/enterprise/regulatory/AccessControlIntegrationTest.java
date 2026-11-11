package com.enterprise.regulatory;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may act on or read what: candidate groups, separation of duties, read scoping, and
 * that clients cannot set process variables.
 */
class AccessControlIntegrationTest extends IntegrationTestBase {
    
    @Test
    void enforcesCandidateGroupOnTaskCompletion() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String manager = login("manager");
        
        // Submitted by the admin, so neither reviewer nor manager is the submitter.
        String pid = startWorkflow(admin, "Task authz flow");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        
        // A manager is not a candidate for the REVIEWER's Initial Review task.
        assertThat(complete(manager, initialReview).getStatusCode().value()).isEqualTo(403);
        // The reviewer is.
        assertThat(complete(reviewer, initialReview).getStatusCode().value()).isEqualTo(200);
        
        // Next stage is Manager Approval (candidate MANAGER).
        String managerApproval = awaitTask(manager, pid, "Task_ManagerApproval");
        assertThat(complete(reviewer, managerApproval).getStatusCode().value()).isEqualTo(403);
        assertThat(complete(manager, managerApproval).getStatusCode().value()).isEqualTo(200);
    }
    
    @Test
    void submitterCannotApproveTheirOwnRequest() {
        String reviewer = login("reviewer");
        String manager = login("manager");
        
        // The manager submits; the reviewer (not the submitter) approves Initial Review.
        String pid = startWorkflow(manager, "Own request");
        complete(reviewer, awaitTask(reviewer, pid, "Task_InitialReview"));
        
        // Manager Approval is a MANAGER task, but this manager submitted the request:
        // it is not offered to them, and claiming or completing it is refused.
        String managerApproval = awaitTaskOfProcess(login("admin"), pid, "Task_ManagerApproval");
        assertThat(taskIdsOf(manager, pid)).doesNotContain(managerApproval);
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + managerApproval + "/claim", manager, null)
                       .getStatusCode().value()).isEqualTo(403);
        ResponseEntity<String> res = complete(manager, managerApproval);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody()).contains("Separation of duties");
    }
    
    @Test
    void submitterCanStillProvideRequestedInformation() {
        String reviewer = login("reviewer");
        String manager = login("manager");
        
        // "Provide Additional Information" is assigned to the submitter by design.
        String pid = startWorkflow(manager, "Needs info");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + initialReview + "/complete", reviewer,
            Map.of("decision", "NEEDS_INFO")).getStatusCode().value()).isEqualTo(200);
        assertThat(complete(manager, awaitTask(manager, pid, "Task_RequestInfo")).getStatusCode().value())
            .isEqualTo(200);
    }
    
    @Test
    void clientCannotSetProcessVariables() {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String manager = login("manager");
        
        String pid = startWorkflow(admin, "Variable injection attempt");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        // Extra properties such as additionalVariables are ignored.
        assertThat(call(HttpMethod.POST, "/api/v1/tasks/" + initialReview + "/complete", reviewer,
            Map.of("decision", "APPROVED",
                "additionalVariables", Map.of("riskScore", "abc", "submitterId", "someone-else")))
                       .getStatusCode().value()).isEqualTo(200);
        
        // Previously an injected non-numeric riskScore broke every manager's task list (500).
        String managerApproval = awaitTask(manager, pid, "Task_ManagerApproval");
        JsonNode task = json(call(HttpMethod.GET, "/api/v1/tasks/" + managerApproval, manager, null)).get("data");
        assertThat(task.get("riskScore").isInt()).isTrue();
    }
    
    @Test
    void scopesSingleItemReadsToOwnerOrOversight() {
        String reviewer = login("reviewer");
        String manager = login("manager");
        String auditor = login("auditor");
        
        // Manager submits a request; reviewer is neither submitter nor oversight.
        String pid = startWorkflow(manager, "IDOR flow");
        String requestId = requestIdOf(manager, pid);
        
        assertThat(call(HttpMethod.GET, "/api/v1/workflow/" + requestId, reviewer, null)
                       .getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/v1/workflow/status/" + pid, reviewer, null)
                       .getStatusCode().value()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, "/api/v1/tasks/process/" + pid, reviewer, null)
                       .getStatusCode().value()).isEqualTo(403);
        
        // Submitter and oversight roles may read it.
        assertThat(call(HttpMethod.GET, "/api/v1/workflow/" + requestId, manager, null)
                       .getStatusCode().value()).isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/workflow/" + requestId, auditor, null)
                       .getStatusCode().value()).isEqualTo(200);
    }
}
