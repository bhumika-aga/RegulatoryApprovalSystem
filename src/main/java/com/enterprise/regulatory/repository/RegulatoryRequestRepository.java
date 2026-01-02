package com.enterprise.regulatory.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.enterprise.regulatory.model.entity.RegulatoryRequest;
import com.enterprise.regulatory.model.enums.ApprovalStatus;

@Repository
public interface RegulatoryRequestRepository extends JpaRepository<RegulatoryRequest, UUID> {

        Optional<RegulatoryRequest> findByProcessInstanceId(String processInstanceId);

        List<RegulatoryRequest> findBySubmitterIdOrderByCreatedAtDesc(String submitterId);

        Page<RegulatoryRequest> findBySubmitterId(String submitterId, Pageable pageable);

        List<RegulatoryRequest> findByStatusOrderByCreatedAtDesc(ApprovalStatus status);

        Page<RegulatoryRequest> findByStatus(ApprovalStatus status, Pageable pageable);

        List<RegulatoryRequest> findByCurrentAssigneeOrderByCreatedAtDesc(String assignee);

        List<RegulatoryRequest> findByDepartmentOrderByCreatedAtDesc(String department);

        @Query("SELECT r FROM RegulatoryRequest r WHERE r.escalated = true ORDER BY r.escalatedAt DESC")
        List<RegulatoryRequest> findEscalatedRequests();

        @Query("SELECT r FROM RegulatoryRequest r WHERE r.status IN :statuses ORDER BY r.createdAt DESC")
        List<RegulatoryRequest> findByStatusIn(@Param("statuses") List<ApprovalStatus> statuses);

        @Query("SELECT r FROM RegulatoryRequest r WHERE r.createdAt BETWEEN :startDate AND :endDate ORDER BY r.createdAt DESC")
        List<RegulatoryRequest> findByDateRange(
                        @Param("startDate") LocalDateTime startDate,
                        @Param("endDate") LocalDateTime endDate);

        @Query("SELECT COUNT(r) FROM RegulatoryRequest r WHERE r.status = :status")
        long countByStatus(@Param("status") ApprovalStatus status);

        @Query("SELECT r.department, COUNT(r) FROM RegulatoryRequest r GROUP BY r.department")
        List<Object[]> countByDepartment();

        @Modifying
        @Query("UPDATE RegulatoryRequest r SET r.currentAssignee = :assignee, r.updatedAt = CURRENT_TIMESTAMP WHERE r.processInstanceId = :processInstanceId")
        int updateCurrentAssignee(
                        @Param("processInstanceId") String processInstanceId,
                        @Param("assignee") String assignee);

        @Modifying
        @Query("UPDATE RegulatoryRequest r SET r.status = :status, r.currentStage = :stage, r.updatedAt = CURRENT_TIMESTAMP WHERE r.processInstanceId = :processInstanceId")
        int updateStatusAndStage(
                        @Param("processInstanceId") String processInstanceId,
                        @Param("status") ApprovalStatus status,
                        @Param("stage") String stage);
}
