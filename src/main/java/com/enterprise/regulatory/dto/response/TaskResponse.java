package com.enterprise.regulatory.dto.response;

import java.util.Date;
import java.util.Map;
import java.util.Set;

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
public class TaskResponse {

    private String taskId;
    private String taskName;
    private String taskDefinitionKey;
    private String processInstanceId;
    private String processDefinitionKey;
    private String assignee;
    private Set<String> candidateGroups;
    private Date createTime;
    private Date dueDate;
    private String description;
    private Integer priority;
    private Map<String, Object> variables;

    // Request details
    private String requestTitle;
    private String requestType;
    private String department;
    private Integer riskScore;
    private String riskCategory;
    private Boolean escalated;
}
