package com.enterprise.regulatory.model.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.enterprise.regulatory.model.enums.ApprovalStatus;
import com.enterprise.regulatory.model.enums.ComplianceResult;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "regulatory_request", indexes = {
        @Index(name = "idx_request_process_instance", columnList = "process_instance_id"),
        @Index(name = "idx_request_status", columnList = "status"),
        @Index(name = "idx_request_submitter", columnList = "submitter_id"),
        @Index(name = "idx_request_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegulatoryRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "process_instance_id", unique = true, length = 64)
    private String processInstanceId;

    @Column(name = "request_title", nullable = false, length = 255)
    private String requestTitle;

    @Column(name = "request_description", columnDefinition = "TEXT")
    private String requestDescription;

    @Column(name = "request_type", nullable = false, length = 100)
    private String requestType;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "priority", length = 20)
    private String priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ApprovalStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "compliance_result", length = 30)
    private ComplianceResult complianceResult;

    @Column(name = "risk_score")
    private Integer riskScore;

    @Column(name = "submitter_id", nullable = false, length = 100)
    private String submitterId;

    @Column(name = "submitter_name", length = 200)
    private String submitterName;

    @Column(name = "current_assignee", length = 100)
    private String currentAssignee;

    @Column(name = "current_stage", length = 100)
    private String currentStage;

    @Column(name = "reviewer_decision", length = 20)
    private String reviewerDecision;

    @Column(name = "reviewer_comment", columnDefinition = "TEXT")
    private String reviewerComment;

    @Column(name = "manager_decision", length = 20)
    private String managerDecision;

    @Column(name = "manager_comment", columnDefinition = "TEXT")
    private String managerComment;

    @Column(name = "compliance_comment", columnDefinition = "TEXT")
    private String complianceComment;

    @Column(name = "final_decision", length = 20)
    private String finalDecision;

    @Column(name = "final_comment", columnDefinition = "TEXT")
    private String finalComment;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "escalated")
    private Boolean escalated;

    @Column(name = "escalation_reason", columnDefinition = "TEXT")
    private String escalationReason;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "due_date")
    private LocalDateTime dueDate;

    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = ApprovalStatus.PENDING;
        }
        if (escalated == null) {
            escalated = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
