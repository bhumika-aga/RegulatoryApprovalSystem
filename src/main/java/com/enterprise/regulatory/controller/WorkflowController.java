package com.enterprise.regulatory.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.regulatory.dto.request.StartWorkflowRequest;
import com.enterprise.regulatory.dto.response.ApiResponse;
import com.enterprise.regulatory.dto.response.WorkflowResponse;
import com.enterprise.regulatory.model.enums.ApprovalStatus;
import com.enterprise.regulatory.service.WorkflowService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST API controller for workflow operations.
 */
@RestController
@RequestMapping("/api/v1/workflow")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Workflow", description = "Regulatory approval workflow management")
@SecurityRequirement(name = "bearerAuth")
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping("/start")
    @Operation(summary = "Start a new workflow", description = "Initiates a new regulatory approval workflow")
    @PreAuthorize("hasAnyRole('REVIEWER', 'MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<WorkflowResponse>> startWorkflow(
            @Valid @RequestBody StartWorkflowRequest request) {
        log.info("Starting new workflow for request: {}", request.getRequestTitle());
        WorkflowResponse response = workflowService.startWorkflow(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Workflow started successfully"));
    }

    @GetMapping("/status/{processInstanceId}")
    @Operation(summary = "Get workflow status", description = "Retrieves the current status of a workflow")
    public ResponseEntity<ApiResponse<WorkflowResponse>> getWorkflowStatus(
            @Parameter(description = "Process instance ID") @PathVariable String processInstanceId) {
        log.debug("Fetching workflow status for: {}", processInstanceId);
        WorkflowResponse response = workflowService.getWorkflowStatus(processInstanceId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{requestId}")
    @Operation(summary = "Get workflow by ID", description = "Retrieves workflow details by request ID")
    public ResponseEntity<ApiResponse<WorkflowResponse>> getWorkflowById(
            @Parameter(description = "Request UUID") @PathVariable UUID requestId) {
        log.debug("Fetching workflow by ID: {}", requestId);
        WorkflowResponse response = workflowService.getWorkflowById(requestId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/my-requests")
    @Operation(summary = "Get my workflows", description = "Retrieves all workflows submitted by the current user")
    public ResponseEntity<ApiResponse<List<WorkflowResponse>>> getMyWorkflows() {
        log.debug("Fetching workflows for current user");
        List<WorkflowResponse> response = workflowService.getMyWorkflows();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get workflows by user", description = "Retrieves all workflows submitted by a specific user")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<List<WorkflowResponse>>> getWorkflowsByUser(
            @Parameter(description = "User ID") @PathVariable String userId) {
        log.debug("Fetching workflows for user: {}", userId);
        List<WorkflowResponse> response = workflowService.getWorkflowsByUser(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/by-status/{status}")
    @Operation(summary = "Get workflows by status", description = "Retrieves all workflows with a specific status")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<List<WorkflowResponse>>> getWorkflowsByStatus(
            @Parameter(description = "Approval status") @PathVariable ApprovalStatus status) {
        log.debug("Fetching workflows with status: {}", status);
        List<WorkflowResponse> response = workflowService.getWorkflowsByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/escalated")
    @Operation(summary = "Get escalated workflows", description = "Retrieves all workflows that have been escalated")
    @PreAuthorize("hasAnyRole('SENIOR_MANAGER', 'ADMIN', 'AUDITOR')")
    public ResponseEntity<ApiResponse<List<WorkflowResponse>>> getEscalatedWorkflows() {
        log.debug("Fetching escalated workflows");
        List<WorkflowResponse> response = workflowService.getEscalatedWorkflows();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @DeleteMapping("/{processInstanceId}")
    @Operation(summary = "Terminate workflow", description = "Terminates a running workflow")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> terminateWorkflow(
            @Parameter(description = "Process instance ID") @PathVariable String processInstanceId,
            @Parameter(description = "Termination reason") @RequestParam String reason) {
        log.info("Terminating workflow: {} with reason: {}", processInstanceId, reason);
        workflowService.terminateWorkflow(processInstanceId, reason);
        return ResponseEntity.ok(ApiResponse.success(null, "Workflow terminated successfully"));
    }
}
