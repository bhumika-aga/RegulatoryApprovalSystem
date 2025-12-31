package com.enterprise.regulatory.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

import com.enterprise.regulatory.model.enums.AuditEventType;
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
public class AuditResponse {

    private UUID id;
    private String processInstanceId;
    private String taskId;
    private String taskName;
    private AuditEventType eventType;
    private String oldValue;
    private String newValue;
    private String performedBy;
    private String role;
    private String comment;
    private LocalDateTime timestamp;
}
