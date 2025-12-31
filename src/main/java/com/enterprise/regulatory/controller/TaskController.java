package com.enterprise.regulatory.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.regulatory.dto.request.CompleteTaskRequest;
import com.enterprise.regulatory.dto.response.ApiResponse;
import com.enterprise.regulatory.dto.response.TaskResponse;
import com.enterprise.regulatory.service.WorkflowTaskService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST API controller for task operations.
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Tasks", description = "Workflow task management")
@SecurityRequirement(name = "bearerAuth")
public class TaskController {

    private final WorkflowTaskService taskService;

    @GetMapping
    @Operation(summary = "Get my tasks", description = "Retrieves all tasks assigned to or available for the current user")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getMyTasks() {
        log.debug("Fetching tasks for current user");
        List<TaskResponse> tasks = taskService.getTasksForCurrentUser();
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping("/process/{processInstanceId}")
    @Operation(summary = "Get tasks by process", description = "Retrieves all active tasks for a workflow instance")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getTasksByProcess(
            @Parameter(description = "Process instance ID") @PathVariable String processInstanceId) {
        log.debug("Fetching tasks for process: {}", processInstanceId);
        List<TaskResponse> tasks = taskService.getTasksByProcessInstance(processInstanceId);
        return ResponseEntity.ok(ApiResponse.success(tasks));
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "Get task details", description = "Retrieves details of a specific task")
    public ResponseEntity<ApiResponse<TaskResponse>> getTask(
            @Parameter(description = "Task ID") @PathVariable String taskId) {
        log.debug("Fetching task: {}", taskId);
        TaskResponse task = taskService.getTask(taskId);
        return ResponseEntity.ok(ApiResponse.success(task));
    }

    @PostMapping("/{taskId}/claim")
    @Operation(summary = "Claim task", description = "Claims a task for the current user")
    public ResponseEntity<ApiResponse<TaskResponse>> claimTask(
            @Parameter(description = "Task ID") @PathVariable String taskId) {
        log.info("Claiming task: {}", taskId);
        TaskResponse task = taskService.claimTask(taskId);
        return ResponseEntity.ok(ApiResponse.success(task, "Task claimed successfully"));
    }

    @PostMapping("/{taskId}/unclaim")
    @Operation(summary = "Unclaim task", description = "Releases a task claimed by the current user")
    public ResponseEntity<ApiResponse<Void>> unclaimTask(
            @Parameter(description = "Task ID") @PathVariable String taskId) {
        log.info("Unclaiming task: {}", taskId);
        taskService.unclaimTask(taskId);
        return ResponseEntity.ok(ApiResponse.success(null, "Task unclaimed successfully"));
    }

    @PostMapping("/{taskId}/complete")
    @Operation(summary = "Complete task", description = "Completes a task with a decision and optional comments")
    public ResponseEntity<ApiResponse<Void>> completeTask(
            @Parameter(description = "Task ID") @PathVariable String taskId,
            @Valid @RequestBody CompleteTaskRequest request) {
        log.info("Completing task: {} with decision: {}", taskId, request.getDecision());
        taskService.completeTask(taskId, request);
        return ResponseEntity.ok(ApiResponse.success(null, "Task completed successfully"));
    }
}
