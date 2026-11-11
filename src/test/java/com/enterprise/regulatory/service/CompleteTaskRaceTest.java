package com.enterprise.regulatory.service;

import com.enterprise.regulatory.dto.request.CompleteTaskRequest;
import com.enterprise.regulatory.exception.TaskOperationException;
import com.enterprise.regulatory.repository.RegulatoryRequestRepository;
import com.enterprise.regulatory.security.SecurityUtils;
import com.enterprise.regulatory.security.UserPrincipal;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskAlreadyClaimedException;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.task.IdentityLink;
import org.camunda.bpm.engine.task.Task;
import org.camunda.bpm.engine.task.TaskQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Two candidates completing the same unclaimed task: the one whose auto-claim loses gets a
 * 409 (TaskOperationException), not a 500.
 */
class CompleteTaskRaceTest {
    
    @Test
    void losingTheAutoClaimRaceIsAConflict() {
        TaskService taskService = mock(TaskService.class);
        Task task = mock(Task.class);
        when(task.getId()).thenReturn("t1");
        when(task.getName()).thenReturn("Initial Review");
        when(task.getTaskDefinitionKey()).thenReturn("Task_InitialReview");
        when(task.getProcessInstanceId()).thenReturn("p1");
        TaskQuery query = mock(TaskQuery.class);
        when(taskService.createTaskQuery()).thenReturn(query);
        when(query.taskId(anyString())).thenReturn(query);
        when(query.singleResult()).thenReturn(task);
        IdentityLink candidate = mock(IdentityLink.class);
        when(candidate.getType()).thenReturn("candidate");
        when(candidate.getGroupId()).thenReturn("REVIEWER");
        when(taskService.getIdentityLinksForTask("t1")).thenReturn(List.of(candidate));
        // Another candidate claimed it between our read and our claim.
        doThrow(new TaskAlreadyClaimedException("t1", "manager")).when(taskService).claim("t1", "reviewer");
        
        SecurityUtils securityUtils = mock(SecurityUtils.class);
        when(securityUtils.getCurrentUser())
            .thenReturn(Optional.of(UserPrincipal.create("reviewer", "OPERATIONS", List.of("REVIEWER"))));
        RegulatoryRequestRepository requests = mock(RegulatoryRequestRepository.class);
        when(requests.findByProcessInstanceIdIn(anyCollection())).thenReturn(List.of());
        
        WorkflowTaskService service = new WorkflowTaskService(taskService, mock(RuntimeService.class), requests,
            securityUtils);
        
        assertThatThrownBy(() -> service.completeTask("t1",
            CompleteTaskRequest.builder().decision("APPROVED").build()))
            .isInstanceOf(TaskOperationException.class)
            .hasMessageContaining("assigned to another user: manager");
        verify(taskService, never()).complete(anyString(), anyMap());
    }
}
