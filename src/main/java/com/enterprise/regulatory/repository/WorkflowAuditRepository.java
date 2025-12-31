package com.enterprise.regulatory.repository;

import com.enterprise.regulatory.model.entity.WorkflowAudit;
import com.enterprise.regulatory.model.enums.AuditEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface WorkflowAuditRepository extends JpaRepository<WorkflowAudit, UUID> {

    List<WorkflowAudit> findByProcessInstanceIdOrderByTimestampDesc(String processInstanceId);

    Page<WorkflowAudit> findByProcessInstanceId(String processInstanceId, Pageable pageable);

    List<WorkflowAudit> findByTaskIdOrderByTimestampDesc(String taskId);

    List<WorkflowAudit> findByPerformedByOrderByTimestampDesc(String performedBy);

    Page<WorkflowAudit> findByPerformedBy(String performedBy, Pageable pageable);

    List<WorkflowAudit> findByEventTypeOrderByTimestampDesc(AuditEventType eventType);

    @Query("SELECT wa FROM WorkflowAudit wa WHERE wa.timestamp BETWEEN :startDate AND :endDate ORDER BY wa.timestamp DESC")
    List<WorkflowAudit> findByDateRange(
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate
    );

    @Query("SELECT wa FROM WorkflowAudit wa WHERE wa.processInstanceId = :processInstanceId AND wa.eventType = :eventType ORDER BY wa.timestamp DESC")
    List<WorkflowAudit> findByProcessInstanceIdAndEventType(
            @Param("processInstanceId") String processInstanceId,
            @Param("eventType") AuditEventType eventType
    );

    @Query("SELECT COUNT(wa) FROM WorkflowAudit wa WHERE wa.eventType = :eventType AND wa.timestamp >= :since")
    long countEventsSince(@Param("eventType") AuditEventType eventType, @Param("since") LocalDateTime since);

    @Query("SELECT DISTINCT wa.processInstanceId FROM WorkflowAudit wa WHERE wa.eventType = 'SLA_BREACH' AND wa.timestamp >= :since")
    List<String> findProcessInstancesWithSlaBreaches(@Param("since") LocalDateTime since);
}
