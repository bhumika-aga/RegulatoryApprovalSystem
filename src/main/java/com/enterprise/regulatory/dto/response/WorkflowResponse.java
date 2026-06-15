package com.enterprise.regulatory.dto.response;

import com.enterprise.regulatory.model.enums.ApprovalStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowResponse {
    
    private UUID requestId;
    private String processInstanceId;
    private String requestTitle;
    private String requestType;
    private String department;
    private String priority;
    private ApprovalStatus status;
    private String currentStage;
    private String currentAssignee;
    private String submitterId;
    private Integer riskScore;
    private Boolean escalated;
    private String escalationReason;
    private String rejectionReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private String message;
}
