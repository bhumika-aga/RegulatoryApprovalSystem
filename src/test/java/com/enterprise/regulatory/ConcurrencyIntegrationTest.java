package com.enterprise.regulatory;

import com.enterprise.regulatory.repository.RegulatoryRequestRepository;
import com.enterprise.regulatory.service.RequestUpdateService;
import org.camunda.bpm.engine.ManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent updates of the same request or task by API calls and workers: retried, and
 * never a server error.
 */
class ConcurrencyIntegrationTest extends IntegrationTestBase {
    
    @Autowired
    private ManagementService managementService;
    
    @Autowired
    private RequestUpdateService requestUpdateService;
    
    @Autowired
    private RegulatoryRequestRepository requestRepository;
    
    @Autowired
    private TransactionTemplate transactionTemplate;
    
    @Test
    void aConcurrentUpdateIsRetriedWithoutLosingEitherChange() throws Exception {
        String pid = startWorkflow(login("admin"), "Concurrent update");
        java.util.UUID requestId = java.util.UUID.fromString(requestIdOf(login("admin"), pid));
        
        java.util.concurrent.CountDownLatch loaded = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch otherCommitted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        
        // A worker-style update reads the request, then waits while another transaction
        // updates and commits the same row, so its own commit hits a version conflict.
        var update = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                                                                             requestUpdateService.update(requestId.toString(), request -> {
                                                                                 if (attempts.incrementAndGet() == 1) {
                                                                                     loaded.countDown();
                                                                                     await(otherCommitted);
                                                                                 }
                                                                                 request.setPriority("URGENT");
                                                                             }));
        await(loaded);
        transactionTemplate.executeWithoutResult(status -> {
            var request = requestRepository.findById(requestId).orElseThrow();
            request.setRequestDescription("changed concurrently");
            requestRepository.save(request);
        });
        otherCommitted.countDown();
        
        assertThat(update.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(attempts.get()).isGreaterThanOrEqualTo(2);
        var request = requestRepository.findById(requestId).orElseThrow();
        assertThat(request.getPriority()).isEqualTo("URGENT");
        assertThat(request.getRequestDescription()).isEqualTo("changed concurrently");
    }
    
    @Test
    void terminatingWhileTheWorkersAreBusyNeverFails() {
        String admin = login("admin");
        for (int i = 0; i < 10; i++) {
            String pid = startWorkflow(admin, "Terminate race " + i);
            assertThat(call(HttpMethod.DELETE, "/api/v1/workflow/" + pid + "?reason=race", admin, null)
                           .getStatusCode().value()).as("terminate %d", i).isEqualTo(200);
        }
    }
    
    @Test
    void twoCandidatesCompletingAtOnceNeverFail() throws Exception {
        String admin = login("admin");
        String reviewer = login("reviewer");
        String manager = login("manager");
        String pid = startWorkflow(admin, "Simultaneous completion");
        String initialReview = awaitTask(reviewer, pid, "Task_InitialReview");
        // After the SLA escalation both REVIEWER and MANAGER are candidates for the task.
        managementService.executeJob(managementService.createJobQuery().processInstanceId(pid).timers()
                                         .singleResult().getId());
        awaitTask(manager, pid, "Task_InitialReview");
        
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                await(start);
                return complete(reviewer, initialReview).getStatusCode().value();
            });
            var second = pool.submit(() -> {
                await(start);
                return complete(manager, initialReview).getStatusCode().value();
            });
            start.countDown();
            java.util.List<Integer> statuses = java.util.List.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                second.get(30, java.util.concurrent.TimeUnit.SECONDS));
            // Exactly one wins. The other loses the claim (409) or arrives once the task is
            // already completed (404), depending on timing; never a 500.
            assertThat(statuses).containsOnlyOnce(200);
            assertThat(statuses).allMatch(status -> status == 200 || status == 404 || status == 409);
        } finally {
            pool.shutdownNow();
        }
    }
}
