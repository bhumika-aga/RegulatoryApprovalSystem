package com.enterprise.regulatory.repository;

import com.enterprise.regulatory.model.entity.RegulatoryRequest;
import com.enterprise.regulatory.model.enums.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RegulatoryRequestRepository extends JpaRepository<RegulatoryRequest, UUID> {
    
    Optional<RegulatoryRequest> findByProcessInstanceId(String processInstanceId);
    
    List<RegulatoryRequest> findBySubmitterIdOrderByCreatedAtDesc(String submitterId);
    
    List<RegulatoryRequest> findByStatusOrderByCreatedAtDesc(ApprovalStatus status);
    
    @Query("SELECT r FROM RegulatoryRequest r WHERE r.escalated = true ORDER BY r.escalatedAt DESC")
    List<RegulatoryRequest> findEscalatedRequests();
}
