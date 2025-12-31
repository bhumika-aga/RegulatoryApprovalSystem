package com.enterprise.regulatory.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.enterprise.regulatory.dto.response.ApiResponse;
import com.enterprise.regulatory.dto.response.AuditResponse;
import com.enterprise.regulatory.model.entity.WorkflowAudit;
import com.enterprise.regulatory.model.enums.AuditEventType;
import com.enterprise.regulatory.service.AuditService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST API controller for audit trail operations.
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Audit", description = "Workflow audit trail operations")
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasAnyRole('AUDITOR', 'ADMIN', 'COMPLIANCE')")
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/process/{processInstanceId}")
    @Operation(summary = "Get audit trail for process", description = "Retrieves all audit events for a workflow instance")
    public ResponseEntity<ApiResponse<List<AuditResponse>>> getAuditTrailForProcess(
            @Parameter(description = "Process instance ID") @PathVariable String processInstanceId) {
        log.debug("Fetching audit trail for process: {}", processInstanceId);
        List<AuditResponse> audits = auditService.getAuditTrailForProcess(processInstanceId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(audits));
    }

    @GetMapping("/task/{taskId}")
    @Operation(summary = "Get audit trail for task", description = "Retrieves all audit events for a specific task")
    public ResponseEntity<ApiResponse<List<AuditResponse>>> getAuditTrailForTask(
            @Parameter(description = "Task ID") @PathVariable String taskId) {
        log.debug("Fetching audit trail for task: {}", taskId);
        List<AuditResponse> audits = auditService.getAuditTrailForTask(taskId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(audits));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Get audit trail by user", description = "Retrieves all audit events performed by a specific user")
    public ResponseEntity<ApiResponse<List<AuditResponse>>> getAuditTrailByUser(
            @Parameter(description = "User ID") @PathVariable String userId) {
        log.debug("Fetching audit trail for user: {}", userId);
        List<AuditResponse> audits = auditService.getAuditTrailByUser(userId)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(audits));
    }

    @GetMapping("/event-type/{eventType}")
    @Operation(summary = "Get audits by event type", description = "Retrieves all audit events of a specific type")
    public ResponseEntity<ApiResponse<List<AuditResponse>>> getAuditByEventType(
            @Parameter(description = "Event type") @PathVariable AuditEventType eventType) {
        log.debug("Fetching audits for event type: {}", eventType);
        List<AuditResponse> audits = auditService.getAuditByEventType(eventType)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(audits));
    }

    @GetMapping("/date-range")
    @Operation(summary = "Get audits by date range", description = "Retrieves all audit events within a date range")
    public ResponseEntity<ApiResponse<List<AuditResponse>>> getAuditByDateRange(
            @Parameter(description = "Start date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @Parameter(description = "End date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        log.debug("Fetching audits from {} to {}", startDate, endDate);
        List<AuditResponse> audits = auditService.getAuditByDateRange(startDate, endDate)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(audits));
    }

    @GetMapping("/sla-breaches")
    @Operation(summary = "Get SLA breaches", description = "Retrieves process instances with SLA breaches since a given date")
    public ResponseEntity<ApiResponse<List<String>>> getSlaBreaches(
            @Parameter(description = "Since date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        log.debug("Fetching SLA breaches since: {}", since);
        List<String> processInstances = auditService.getProcessInstancesWithSlaBreaches(since);
        return ResponseEntity.ok(ApiResponse.success(processInstances));
    }

    @GetMapping("/count/{eventType}")
    @Operation(summary = "Count events", description = "Counts audit events of a specific type since a given date")
    public ResponseEntity<ApiResponse<Long>> countEventsSince(
            @Parameter(description = "Event type") @PathVariable AuditEventType eventType,
            @Parameter(description = "Since date (ISO format)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since) {
        log.debug("Counting {} events since {}", eventType, since);
        long count = auditService.countEventsSince(eventType, since);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    private AuditResponse toResponse(WorkflowAudit audit) {
        return AuditResponse.builder()
                .id(audit.getId())
                .processInstanceId(audit.getProcessInstanceId())
                .taskId(audit.getTaskId())
                .taskName(audit.getTaskName())
                .eventType(audit.getEventType())
                .oldValue(audit.getOldValue())
                .newValue(audit.getNewValue())
                .performedBy(audit.getPerformedBy())
                .role(audit.getRole())
                .comment(audit.getComment())
                .timestamp(audit.getTimestamp())
                .build();
    }
}
