package com.enterprise.regulatory.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.Task;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.enterprise.regulatory.dto.request.CompleteTaskRequest;
import com.enterprise.regulatory.dto.response.TaskResponse;
import com.enterprise.regulatory.exception.ResourceNotFoundException;
import com.enterprise.regulatory.exception.TaskOperationException;
import com.enterprise.regulatory.repository.RegulatoryRequestRepository;
import com.enterprise.regulatory.security.SecurityUtils;
import com.enterprise.regulatory.security.UserPrincipal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing workflow tasks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowTaskService {

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

        return allTasks.stream()
                .map(this::buildTaskResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getTasksByProcessInstance(String processInstanceId) {
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

        return buildTaskResponse(task);
    }

    @Transactional
    public TaskResponse claimTask(String taskId) {
        String username = securityUtils.getCurrentUsername();

        Task task = camundaTaskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();

        if (task == null) {
            throw new ResourceNotFoundException("Task not found with ID: " + taskId);
        }

        if (task.getAssignee() != null && !task.getAssignee().equals(username)) {
            throw new TaskOperationException("Task is already claimed by: " + task.getAssignee());
        }

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
        String username = securityUtils.getCurrentUsername();

        Task task = camundaTaskService.createTaskQuery()
                .taskId(taskId)
                .singleResult();

        if (task == null) {
            throw new ResourceNotFoundException("Task not found with ID: " + taskId);
        }

        // Auto-claim if not assigned
        if (task.getAssignee() == null) {
            camundaTaskService.claim(taskId, username);
        } else if (!task.getAssignee().equals(username)) {
            throw new TaskOperationException("Task is assigned to another user: " + task.getAssignee());
        }

        try {
            Map<String, Object> variables = buildCompletionVariables(task, request);

            camundaTaskService.complete(taskId, variables);

            // Update regulatory request with decision
            updateRequestWithDecision(task.getProcessInstanceId(), task.getName(), request);

            log.info("Task {} completed by user: {} with decision: {}", taskId, username, request.getDecision());
        } catch (Exception e) {
            log.error("Failed to complete task: {}", taskId, e);
            throw new TaskOperationException("Failed to complete task: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildCompletionVariables(Task task, CompleteTaskRequest request) {
        Map<String, Object> variables = new HashMap<>();
        String taskName = task.getName();

        // Set decision variable based on task
        if (taskName.contains("Initial Review")) {
            variables.put("reviewerDecision", request.getDecision());
            if (request.getComment() != null) {
                variables.put("reviewerComment", request.getComment());
            }
        } else if (taskName.contains("Senior Manager")) {
            variables.put("seniorManagerDecision", request.getDecision());
            if (request.getComment() != null) {
                variables.put("seniorManagerComment", request.getComment());
            }
        } else if (taskName.contains("Manager")) {
            variables.put("managerDecision", request.getDecision());
            if (request.getComment() != null) {
                variables.put("managerComment", request.getComment());
            }
        } else if (taskName.contains("Compliance")) {
            variables.put("manualComplianceDecision", request.getDecision());
            if (request.getComment() != null) {
                variables.put("complianceComment", request.getComment());
            }
        } else if (taskName.contains("Final")) {
            variables.put("finalDecision", request.getDecision());
            if (request.getComment() != null) {
                variables.put("finalComment", request.getComment());
            }
        } else if (taskName.contains("Additional Information")) {
            variables.put("additionalInfoProvided", true);
            if (request.getComment() != null) {
                variables.put("additionalInfo", request.getComment());
            }
        }

        // Add task comment
        if (request.getComment() != null) {
            variables.put("taskComment", request.getComment());
        }

        // Add any additional variables
        if (request.getAdditionalVariables() != null) {
            variables.putAll(request.getAdditionalVariables());
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

    private void updateRequestWithDecision(String processInstanceId, String taskName,
            CompleteTaskRequest request) {
        requestRepository.findByProcessInstanceId(processInstanceId)
                .ifPresent(regulatoryRequest -> {
                    if (taskName.contains("Initial Review")) {
                        regulatoryRequest.setReviewerDecision(request.getDecision());
                        regulatoryRequest.setReviewerComment(request.getComment());
                        regulatoryRequest.setCurrentStage("MANAGER_APPROVAL");
                    } else if (taskName.contains("Senior Manager")) {
                        regulatoryRequest.setCurrentStage("COMPLIANCE_CHECK");
                    } else if (taskName.contains("Manager")) {
                        regulatoryRequest.setManagerDecision(request.getDecision());
                        regulatoryRequest.setManagerComment(request.getComment());
                        regulatoryRequest.setCurrentStage(
                                "ESCALATE".equals(request.getDecision()) ? "SENIOR_MANAGER_REVIEW"
                                        : "COMPLIANCE_CHECK");
                    } else if (taskName.contains("Compliance")) {
                        regulatoryRequest.setComplianceComment(request.getComment());
                        regulatoryRequest.setCurrentStage("FINAL_APPROVAL");
                    } else if (taskName.contains("Final")) {
                        regulatoryRequest.setFinalDecision(request.getDecision());
                        regulatoryRequest.setFinalComment(request.getComment());
                        regulatoryRequest.setCurrentStage("COMPLETED");
                    }

                    regulatoryRequest.setCurrentAssignee(null);
                    requestRepository.save(regulatoryRequest);
                });
    }

    private TaskResponse buildTaskResponse(Task task) {
        Map<String, Object> variables = camundaTaskService.getVariables(task.getId());

        // Get candidate groups
        Set<String> candidateGroups = camundaTaskService.getIdentityLinksForTask(task.getId())
                .stream()
                .filter(link -> "candidate".equals(link.getType()) && link.getGroupId() != null)
                .map(link -> link.getGroupId())
                .collect(Collectors.toSet());

        return TaskResponse.builder()
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
                .build();
    }
}
