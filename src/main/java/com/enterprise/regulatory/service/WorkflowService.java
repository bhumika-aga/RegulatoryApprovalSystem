package com.enterprise.regulatory.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.regulatory.dto.request.StartWorkflowRequest;
import com.enterprise.regulatory.dto.response.WorkflowResponse;
import com.enterprise.regulatory.exception.ResourceNotFoundException;
import com.enterprise.regulatory.exception.WorkflowException;
import com.enterprise.regulatory.model.entity.RegulatoryRequest;
import com.enterprise.regulatory.model.enums.ApprovalStatus;
import com.enterprise.regulatory.repository.RegulatoryRequestRepository;
import com.enterprise.regulatory.security.SecurityUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing regulatory approval workflows.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowService {

    private static final String PROCESS_DEFINITION_KEY = "regulatory-approval-process";

    private final RuntimeService runtimeService;
    private final RegulatoryRequestRepository requestRepository;
    private final SecurityUtils securityUtils;

    @Transactional
    public WorkflowResponse startWorkflow(StartWorkflowRequest request) {
        String username = securityUtils.getCurrentUsername();
        String department = securityUtils.getCurrentUserDepartment().orElse(request.getDepartment());

        log.info("Starting workflow for user: {}, request: {}", username, request.getRequestTitle());

        // Create regulatory request entity
        RegulatoryRequest regulatoryRequest = Objects.requireNonNull(RegulatoryRequest.builder()
                .requestTitle(request.getRequestTitle())
                .requestDescription(request.getRequestDescription())
                .requestType(request.getRequestType())
                .department(department)
                .priority(request.getPriority() != null ? request.getPriority() : "NORMAL")
                .status(ApprovalStatus.PENDING)
                .submitterId(username)
                .currentStage("INITIAL_REVIEW")
                .build());

        regulatoryRequest = requestRepository.save(regulatoryRequest);

        // Prepare process variables
        Map<String, Object> variables = new HashMap<>();
        variables.put("requestId", regulatoryRequest.getId().toString());
        variables.put("requestTitle", request.getRequestTitle());
        variables.put("requestDescription", request.getRequestDescription());
        variables.put("requestType", request.getRequestType());
        variables.put("department", department);
        variables.put("priority", regulatoryRequest.getPriority());
        variables.put("submitterId", username);
        variables.put("escalated", false);

        try {
            // Start the process instance
            ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(
                    PROCESS_DEFINITION_KEY,
                    regulatoryRequest.getId().toString(),
                    variables);

            // Update the request with process instance ID
            regulatoryRequest.setProcessInstanceId(processInstance.getProcessInstanceId());
            regulatoryRequest.setStatus(ApprovalStatus.IN_REVIEW);
            requestRepository.save(regulatoryRequest);

            log.info("Workflow started successfully. ProcessInstanceId: {}, RequestId: {}",
                    processInstance.getProcessInstanceId(), regulatoryRequest.getId());

            return buildWorkflowResponse(regulatoryRequest, "Workflow started successfully");

        } catch (Exception e) {
            log.error("Failed to start workflow for request: {}", regulatoryRequest.getId(), e);
            requestRepository.delete(regulatoryRequest);
            throw new WorkflowException("Failed to start workflow: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public WorkflowResponse getWorkflowStatus(String processInstanceId) {
        RegulatoryRequest request = requestRepository.findByProcessInstanceId(processInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Workflow not found with processInstanceId: " + processInstanceId));

        return buildWorkflowResponse(request, null);
    }

    @Transactional(readOnly = true)
    public WorkflowResponse getWorkflowById(UUID requestId) {
        RegulatoryRequest request = requestRepository.findById(Objects.requireNonNull(requestId))
                .orElseThrow(() -> new ResourceNotFoundException("Request not found with ID: " + requestId));

        return buildWorkflowResponse(request, null);
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> getWorkflowsByUser(String userId) {
        return requestRepository.findBySubmitterIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(r -> buildWorkflowResponse(r, null))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> getWorkflowsByStatus(ApprovalStatus status) {
        return requestRepository.findByStatusOrderByCreatedAtDesc(status)
                .stream()
                .map(r -> buildWorkflowResponse(r, null))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> getEscalatedWorkflows() {
        return requestRepository.findEscalatedRequests()
                .stream()
                .map(r -> buildWorkflowResponse(r, null))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> getMyWorkflows() {
        String username = securityUtils.getCurrentUsername();
        return getWorkflowsByUser(username);
    }

    @Transactional
    public void terminateWorkflow(String processInstanceId, String reason) {
        RegulatoryRequest request = requestRepository.findByProcessInstanceId(processInstanceId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow not found"));

        try {
            runtimeService.deleteProcessInstance(processInstanceId, reason);
            request.setStatus(ApprovalStatus.TERMINATED);
            request.setRejectionReason("Terminated: " + reason);
            requestRepository.save(request);

            log.info("Workflow terminated: {}, reason: {}", processInstanceId, reason);
        } catch (Exception e) {
            log.error("Failed to terminate workflow: {}", processInstanceId, e);
            throw new WorkflowException("Failed to terminate workflow: " + e.getMessage(), e);
        }
    }

    private WorkflowResponse buildWorkflowResponse(RegulatoryRequest request, String message) {
        return Objects.requireNonNull(WorkflowResponse.builder()
                .requestId(request.getId())
                .processInstanceId(request.getProcessInstanceId())
                .requestTitle(request.getRequestTitle())
                .requestType(request.getRequestType())
                .department(request.getDepartment())
                .priority(request.getPriority())
                .status(request.getStatus())
                .currentStage(request.getCurrentStage())
                .currentAssignee(request.getCurrentAssignee())
                .submitterId(request.getSubmitterId())
                .submitterName(request.getSubmitterName())
                .riskScore(request.getRiskScore())
                .escalated(request.getEscalated())
                .escalationReason(request.getEscalationReason())
                .createdAt(request.getCreatedAt())
                .updatedAt(request.getUpdatedAt())
                .completedAt(request.getCompletedAt())
                .message(message)
                .build());
    }
}
