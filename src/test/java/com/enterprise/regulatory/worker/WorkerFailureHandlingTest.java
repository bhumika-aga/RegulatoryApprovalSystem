package com.enterprise.regulatory.worker;

import com.enterprise.regulatory.service.AuditService;
import com.enterprise.regulatory.service.RequestUpdateService;
import org.camunda.bpm.client.ExternalTaskClient;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Camunda hands out a task with {@code null} retries first and never again once retries reach
 * 0, so a worker's fallback must run on the attempt that leaves 0 retries.
 */
class WorkerFailureHandlingTest {
    
    private static ExternalTask taskWithRetries(Integer retries) {
        ExternalTask task = mock(ExternalTask.class);
        when(task.getRetries()).thenReturn(retries);
        when(task.getVariable("requestId")).thenReturn("00000000-0000-0000-0000-000000000001");
        return task;
    }
    
    @Test
    void countsDownToZeroOverThreeAttempts() {
        assertThat(WorkerRetries.remainingAfterFailure(taskWithRetries(null))).isEqualTo(2);
        assertThat(WorkerRetries.remainingAfterFailure(taskWithRetries(2))).isEqualTo(1);
        assertThat(WorkerRetries.remainingAfterFailure(taskWithRetries(1))).isZero();
    }
    
    private RiskScoringWorker failingRiskScoringWorker() {
        RequestUpdateService updates = mock(RequestUpdateService.class);
        when(updates.update(anyString(), any())).thenThrow(new IllegalStateException("database down"));
        return new RiskScoringWorker(mock(ExternalTaskClient.class), updates);
    }
    
    @Test
    void riskScoringRetriesBeforeTheLastAttempt() {
        ExternalTaskService service = mock(ExternalTaskService.class);
        ExternalTask task = taskWithRetries(2);
        failingRiskScoringWorker().execute(task, service);
        
        verify(service).handleFailure(eq(task), anyString(), anyString(), eq(1), anyLong());
        verify(service, never()).complete(any(ExternalTask.class), anyMap());
    }
    
    @Test
    @SuppressWarnings("unchecked")
    void riskScoringFallsBackToTheDefaultScoreOnTheLastAttempt() {
        ExternalTaskService service = mock(ExternalTaskService.class);
        ExternalTask task = taskWithRetries(1);
        failingRiskScoringWorker().execute(task, service);
        
        ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.forClass(Map.class);
        verify(service).complete(eq(task), variables.capture());
        assertThat(variables.getValue()).containsEntry("riskScore", 50).containsEntry("riskCategory", "MEDIUM");
        verify(service, never()).handleFailure(any(ExternalTask.class), anyString(), anyString(), anyInt(), anyLong());
    }
    
    @Test
    @SuppressWarnings("unchecked")
    void riskScoringMovesTheRequestToInitialReview() {
        RequestUpdateService updates = mock(RequestUpdateService.class);
        when(updates.update(anyString(), any())).thenAnswer(call -> {
            var request = new com.enterprise.regulatory.model.entity.RegulatoryRequest();
            request.setCurrentStage("RISK_ASSESSMENT");
            ((java.util.function.Consumer<com.enterprise.regulatory.model.entity.RegulatoryRequest>) call.getArgument(1))
                .accept(request);
            assertThat(request.getCurrentStage()).isEqualTo("INITIAL_REVIEW");
            assertThat(request.getRiskScore()).isNotNull();
            return true;
        });
        ExternalTaskService service = mock(ExternalTaskService.class);
        new RiskScoringWorker(mock(ExternalTaskClient.class), updates).execute(taskWithRetries(null), service);
        
        verify(updates).update(anyString(), any());
        verify(service).complete(any(ExternalTask.class), anyMap());
    }
    
    @Test
    void completionRaisesAnIncidentOnTheLastAttempt() {
        RequestUpdateService updates = mock(RequestUpdateService.class);
        when(updates.update(anyString(), any())).thenThrow(new IllegalStateException("database down"));
        WorkflowCompletionWorker worker = new WorkflowCompletionWorker(
            mock(ExternalTaskClient.class), updates, mock(AuditService.class));
        ExternalTaskService service = mock(ExternalTaskService.class);
        ExternalTask task = taskWithRetries(1);
        when(task.getVariable("outcome")).thenReturn("APPROVED");
        
        worker.execute(task, service);
        
        // 0 retries left: Camunda turns this into an incident an operator can retry.
        verify(service).handleFailure(eq(task), anyString(), anyString(), eq(0), anyLong());
        verify(service, never()).handleBpmnError(any(ExternalTask.class), anyString(), anyString());
    }
}
