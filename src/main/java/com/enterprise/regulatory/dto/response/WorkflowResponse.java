package com.enterprise.regulatory.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.enterprise.regulatory.model.enums.ApprovalStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String submitterName;
    private Integer riskScore;
    private String riskCategory;
    private Boolean escalated;
    private String escalationReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private String message;
}
