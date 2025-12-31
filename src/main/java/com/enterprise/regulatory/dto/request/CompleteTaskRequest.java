package com.enterprise.regulatory.dto.request;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompleteTaskRequest {

    @NotBlank(message = "Decision is required")
    @Pattern(regexp = "^(APPROVED|REJECTED|NEEDS_INFO|ESCALATE|PASS|FAIL)$", message = "Decision must be one of: APPROVED, REJECTED, NEEDS_INFO, ESCALATE, PASS, FAIL")
    private String decision;

    @Size(max = 4000, message = "Comment must not exceed 4000 characters")
    private String comment;

    private Map<String, Object> additionalVariables;
}
