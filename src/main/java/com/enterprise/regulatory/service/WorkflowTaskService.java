package com.enterprise.regulatory.service;

import com.enterprise.regulatory.dto.request.CompleteTaskRequest;
import com.enterprise.regulatory.dto.response.TaskResponse;
import com.enterprise.regulatory.exception.ResourceNotFoundException;
import com.enterprise.regulatory.exception.TaskOperationException;
import com.enterprise.regulatory.model.enums.ComplianceResult;
import com.enterprise.regulatory.model.enums.ReviewStep;
import com.enterprise.regulatory.repository.RegulatoryRequestRepository;
import com.enterprise.regulatory.security.SecurityUtils;
import com.enterprise.regulatory.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.IdentityLink;
import org.camunda.bpm.engine.task.Task;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing workflow tasks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowTaskService {
    
    /**
     * BPMN id of "Provide Additional Information", the one task the submitter works on.
     */
    private static final String SUBMITTER_TASK_DEFINITION_KEY = "Task_RequestInfo";
    
    private final TaskService camundaTaskService;
    private final RegulatoryRequestRepository requestRepository;
    private final SecurityUtils securityUtils;
    
    @Transactional(readOnly = true)
    public List<TaskResponse> getTasksForCurrentUser() {
        Optional<UserPrincipal> userOpt = securityUtils.getCurrentUser();
        if (userOpt.isEmpty()) {
            return Collections.emptyList();
        }
        
        UserPrincipal user = userOpt.get();
        String username = user.getUsername();
        Set<String> roles = user.getRoles();
        
        log.debug("Fetching tasks for user: {} with roles: {}", username, roles);
        
        // Get tasks assigned to user
        List<Task> assignedTasks = camundaTaskService.createTaskQuery()
                                       .taskAssignee(username)
                                       .active()
                                       .orderByTaskCreateTime()
                                       .desc()
                                       .list();
        
        // Get tasks available for user's groups (candidate groups)
        List<Task> candidateTasks = new ArrayList<>();
        for (String role : roles) {
            List<Task> groupTasks = camundaTaskService.createTaskQuery()
                                        .taskCandidateGroup(role)
                                        .taskUnassigned()
                                        .active()
                                        .list();
            candidateTasks.addAll(groupTasks);
        }
        
        // Combine and deduplicate
        Set<String> taskIds = new HashSet<>();
        List<Task> allTasks = new ArrayList<>();
        
        for (Task task : assignedTasks) {
            if (taskIds.add(task.getId())) {
                allTasks.add(task);
            }
        }
        for (Task task : candidateTasks) {
            if (taskIds.add(task.getId())) {
                allTasks.add(task);
            }
        }
        
        // Hide tasks the user may not act on because they submitted the request.
        return allTasks.stream()
                   .filter(task -> !isReviewOfOwnRequest(task, username))
                   .map(this::buildTaskResponse)
                   .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public List<TaskResponse> getTasksByProcessInstance(String processInstanceId) {
        authorizeProcessRead(processInstanceId);
        
        List<Task> tasks = camundaTaskService.createTaskQuery()
                               .processInstanceId(processInstanceId)
                               .active()
                               .orderByTaskCreateTime()
                               .asc()
                               .list();
        
        return tasks.stream()
                   .map(this::buildTaskResponse)
                   .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public TaskResponse getTask(String taskId) {
        Task task = camundaTaskService.createTaskQuery()
                        .taskId(taskId)
                        .singleResult();
        
        if (task == null) {
            throw new ResourceNotFoundException("Task not found with ID: " + taskId);
        }
        
        UserPrincipal user = requireCurrentUser();
        if (!securityUtils.currentUserHasOversight() && canActOnTask(task, user)) {
            log.warn("User {} denied read access to task {}", user.getUsername(), taskId);
            throw new AccessDeniedException("You are not permitted to view this task");
        }
        
        return buildTaskResponse(task);
    }
    
    @Transactional
    public TaskResponse claimTask(String taskId) {
        UserPrincipal user = requireCurrentUser();
        String username = user.getUsername();
        
        Task task = camundaTaskService.createTaskQuery()
                        .taskId(taskId)
                        .singleResult();
        
        if (task == null) {
            throw new ResourceNotFoundException("Task not found with ID: " + taskId);
        }
        
        if (task.getAssignee() != null && !task.getAssignee().equals(username)) {
            throw new TaskOperationException("Task is already claimed by: " + task.getAssignee());
        }
        
        if (canActOnTask(task, user)) {
            log.warn("User {} (roles {}) denied claim of task {}", username, user.getRoles(), taskId);
            throw new AccessDeniedException("Your role is not eligible to work this task");
        }
        enforceSeparationOfDuties(task, username, "claim");
        
        try {
            camundaTaskService.claim(taskId, username);
            
            // Update regulatory request
            updateRequestAssignee(task.getProcessInstanceId(), username);
            
            log.info("Task {} claimed by user: {}", taskId, username);
            
            // Refresh task
            task = camundaTaskService.createTaskQuery()
                       .taskId(taskId)
                       .singleResult();
            
            return buildTaskResponse(task);
            
        } catch (Exception e) {
            log.error("Failed to claim task: {}", taskId, e);
            throw new TaskOperationException("Failed to claim task: " + e.getMessage(), e);
        }
    }
    
    @Transactional
    public void unclaimTask(String taskId) {
        String username = securityUtils.getCurrentUsername();
        
        Task task = camundaTaskService.createTaskQuery()
                        .taskId(taskId)
                        .singleResult();
        
        if (task == null) {
            throw new ResourceNotFoundException("Task not found with ID: " + taskId);
        }
        
        if (task.getAssignee() == null || !task.getAssignee().equals(username)) {
            throw new TaskOperationException("Task is not claimed by current user");
        }
        
        try {
            camundaTaskService.setAssignee(taskId, null);
            updateRequestAssignee(task.getProcessInstanceId(), null);
            
            log.info("Task {} unclaimed by user: {}", taskId, username);
        } catch (Exception e) {
            log.error("Failed to unclaim task: {}", taskId, e);
            throw new TaskOperationException("Failed to unclaim task: " + e.getMessage(), e);
        }
    }
    
    @Transactional
    public void completeTask(String taskId, CompleteTaskRequest request) {
        UserPrincipal user = requireCurrentUser();
        String username = user.getUsername();
        
        Task task = camundaTaskService.createTaskQuery()
                        .taskId(taskId)
                        .singleResult();
        
        if (task == null) {
            throw new ResourceNotFoundException("Task not found with ID: " + taskId);
        }
        
        // The caller's role must be a candidate group for the task (or the task must
        // already be theirs); otherwise a REVIEWER could complete a Final Approval, etc.
        if (canActOnTask(task, user)) {
            log.warn("User {} (roles {}) denied completion of task {} '{}'",
                username, user.getRoles(), taskId, task.getName());
            throw new AccessDeniedException("Your role is not eligible to complete this task");
        }
        enforceSeparationOfDuties(task, username, "complete");
        
        // Only decisions the next gateway can route are accepted; any other would fail
        // inside the engine (no outgoing flow matches).
        ReviewStep step = ReviewStep.of(task.getTaskDefinitionKey())
                              .orElseThrow(() -> new TaskOperationException(
                                  "Task '" + task.getName() + "' cannot be completed through this API"));
        if (!step.accepts(request.getDecision())) {
            throw new IllegalArgumentException("Decision '" + request.getDecision() + "' is not valid for task '"
                                                   + task.getName() + "'; expected one of " + step.decisions());
        }
        
        // Auto-claim if not assigned
        if (task.getAssignee() == null) {
            camundaTaskService.claim(taskId, username);
        } else if (!task.getAssignee().equals(username)) {
            throw new TaskOperationException("Task is assigned to another user: " + task.getAssignee());
        }
        
        try {
            Map<String, Object> variables = buildCompletionVariables(step, request);
            
            camundaTaskService.complete(taskId, variables);
            
            // Update regulatory request with decision
            updateRequestWithDecision(task.getProcessInstanceId(), step, request);
            
            log.info("Task {} completed by user: {} with decision: {}", taskId, username, request.getDecision());
        } catch (Exception e) {
            log.error("Failed to complete task: {}", taskId, e);
            throw new TaskOperationException("Failed to complete task: " + e.getMessage(), e);
        }
    }
    
    private Map<String, Object> buildCompletionVariables(ReviewStep step, CompleteTaskRequest request) {
        Map<String, Object> variables = new HashMap<>();
        if (step.decisionVariable() != null) {
            variables.put(step.decisionVariable(), request.getDecision());
        }
        if (step == ReviewStep.PROVIDE_INFORMATION) {
            variables.put("additionalInfoProvided", true);
        }
        if (request.getComment() != null) {
            variables.put(step.commentVariable(), request.getComment());
            variables.put("taskComment", request.getComment());
        }
        return variables;
    }
    
    private void updateRequestAssignee(String processInstanceId, String assignee) {
        requestRepository.findByProcessInstanceId(processInstanceId)
            .ifPresent(request -> {
                request.setCurrentAssignee(assignee);
                requestRepository.save(request);
            });
    }
    
    private void updateRequestWithDecision(String processInstanceId, ReviewStep step,
                                           CompleteTaskRequest request) {
        String decision = request.getDecision();
        String comment = request.getComment();
        requestRepository.findByProcessInstanceId(processInstanceId)
            .ifPresent(regulatoryRequest -> {
                switch (step) {
                    case INITIAL_REVIEW -> {
                        regulatoryRequest.setReviewerDecision(decision);
                        regulatoryRequest.setReviewerComment(comment);
                    }
                    case MANAGER_APPROVAL -> {
                        regulatoryRequest.setManagerDecision(decision);
                        regulatoryRequest.setManagerComment(comment);
                    }
                    case SENIOR_MANAGER_REVIEW -> {
                        regulatoryRequest.setSeniorManagerDecision(decision);
                        regulatoryRequest.setSeniorManagerComment(comment);
                    }
                    case COMPLIANCE_REVIEW -> {
                        regulatoryRequest.setComplianceResult(ComplianceResult.valueOf(decision));
                        regulatoryRequest.setComplianceComment(comment);
                    }
                    case FINAL_APPROVAL -> {
                        regulatoryRequest.setFinalDecision(decision);
                        regulatoryRequest.setFinalComment(comment);
                    }
                    case PROVIDE_INFORMATION -> {
                        // The answer is kept in the process variables and the audit trail.
                    }
                }
                regulatoryRequest.setCurrentStage(nextStage(step, decision));
                regulatoryRequest.setCurrentAssignee(null);
                requestRepository.save(regulatoryRequest);
            });
    }
    
    /**
     * The stage a request moves to after a step, following the process model's routing.
     * Rejections end the workflow, so they lead to COMPLETED.
     */
    static String nextStage(ReviewStep step, String decision) {
        if ("REJECTED".equals(decision) || "FAIL".equals(decision)) {
            return "COMPLETED";
        }
        return switch (step) {
            case INITIAL_REVIEW -> "NEEDS_INFO".equals(decision) ? "ADDITIONAL_INFORMATION" : "MANAGER_APPROVAL";
            case PROVIDE_INFORMATION -> "INITIAL_REVIEW";
            case MANAGER_APPROVAL -> "ESCALATE".equals(decision) ? "SENIOR_MANAGER_REVIEW" : "COMPLIANCE_CHECK";
            case SENIOR_MANAGER_REVIEW -> "COMPLIANCE_CHECK";
            case COMPLIANCE_REVIEW -> "FINAL_APPROVAL";
            case FINAL_APPROVAL -> "COMPLETED";
        };
    }
    
    /**
     * Candidate group names attached to a task; these mirror JWT role names.
     */
    private Set<String> getCandidateGroups(String taskId) {
        return camundaTaskService.getIdentityLinksForTask(taskId)
                   .stream()
                   .filter(link -> "candidate".equals(link.getType()) && link.getGroupId() != null)
                   .map(IdentityLink::getGroupId)
                   .collect(Collectors.toSet());
    }
    
    /**
     * Separation of duties: nobody may review or approve a request they submitted
     * themselves, whatever their role (e.g. a MANAGER approving their own submission at
     * Manager Approval, or an ADMIN giving Final Approval to their own request).
     */
    private void enforceSeparationOfDuties(Task task, String username, String action) {
        if (isReviewOfOwnRequest(task, username)) {
            log.warn("User {} denied {} of task {} '{}': they submitted the request",
                username, action, task.getId(), task.getName());
            throw new AccessDeniedException(
                "Separation of duties: you cannot " + action + " a review task on a request you submitted");
        }
    }
    
    /**
     * Whether the task is a review/approval step on a request the user submitted.
     * "Provide Additional Information" is excluded: it is assigned to the submitter by design.
     */
    private boolean isReviewOfOwnRequest(Task task, String username) {
        if (SUBMITTER_TASK_DEFINITION_KEY.equals(task.getTaskDefinitionKey())) {
            return false;
        }
        return requestRepository.findByProcessInstanceId(task.getProcessInstanceId())
                   .map(request -> username.equals(request.getSubmitterId()))
                   .orElse(false);
    }
    
    /**
     * Whether a user may NOT act on (claim/complete) a task: returns {@code true} unless
     * the task is already assigned to them or one of their roles is a candidate group.
     */
    private boolean canActOnTask(Task task, UserPrincipal user) {
        if (user.getUsername().equals(task.getAssignee())) {
            return false;
        }
        
        Set<String> candidateGroups = getCandidateGroups(task.getId());
        return user.getRoles().stream().noneMatch(candidateGroups::contains);
    }
    
    private UserPrincipal requireCurrentUser() {
        return securityUtils.getCurrentUser()
                   .orElseThrow(() -> new AccessDeniedException("Authentication required"));
    }
    
    /**
     * Listing every task on a process instance is restricted to oversight roles or
     * the request's submitter; other users act on their own tasks via {@code /tasks}.
     */
    private void authorizeProcessRead(String processInstanceId) {
        if (securityUtils.currentUserHasOversight()) {
            return;
        }
        String username = securityUtils.getCurrentUsername();
        boolean isSubmitter = requestRepository.findByProcessInstanceId(processInstanceId)
                                  .map(r -> username.equals(r.getSubmitterId()))
                                  .orElse(false);
        if (!isSubmitter) {
            log.warn("User {} denied read access to tasks for process {}", username, processInstanceId);
            throw new AccessDeniedException("You are not permitted to view tasks for this workflow");
        }
    }
    
    private TaskResponse buildTaskResponse(Task task) {
        Map<String, Object> variables = camundaTaskService.getVariables(task.getId());
        
        Set<String> candidateGroups = getCandidateGroups(task.getId());
        
        return Objects.requireNonNull(TaskResponse.builder()
                                          .taskId(task.getId())
                                          .taskName(task.getName())
                                          .taskDefinitionKey(task.getTaskDefinitionKey())
                                          .processInstanceId(task.getProcessInstanceId())
                                          .processDefinitionKey(task.getProcessDefinitionId())
                                          .assignee(task.getAssignee())
                                          .candidateGroups(candidateGroups)
                                          .createTime(task.getCreateTime())
                                          .dueDate(task.getDueDate())
                                          .description(task.getDescription())
                                          .priority(task.getPriority())
                                          .requestTitle((String) variables.get("requestTitle"))
                                          .requestType((String) variables.get("requestType"))
                                          .department((String) variables.get("department"))
                                          .riskScore((Integer) variables.get("riskScore"))
                                          .riskCategory((String) variables.get("riskCategory"))
                                          .escalated((Boolean) variables.get("escalated"))
                                          .build());
    }
}
