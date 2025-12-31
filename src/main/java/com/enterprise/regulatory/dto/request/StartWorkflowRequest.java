package com.enterprise.regulatory.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartWorkflowRequest {

    @NotBlank(message = "Request title is required")
    @Size(max = 255, message = "Request title must not exceed 255 characters")
    private String requestTitle;

    @Size(max = 4000, message = "Request description must not exceed 4000 characters")
    private String requestDescription;

    @NotBlank(message = "Request type is required")
    @Size(max = 100, message = "Request type must not exceed 100 characters")
    private String requestType;

    @Size(max = 100, message = "Department must not exceed 100 characters")
    private String department;

    @Size(max = 20, message = "Priority must not exceed 20 characters")
    private String priority;
}
