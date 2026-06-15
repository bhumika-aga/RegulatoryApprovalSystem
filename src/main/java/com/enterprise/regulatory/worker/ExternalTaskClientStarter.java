package com.enterprise.regulatory.worker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.client.ExternalTaskClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Starts the shared {@link ExternalTaskClient} once every worker has opened its
 * topic subscription.
 *
 * <p>
 * The client is created with auto-fetching disabled, so polling only begins when
 * {@link ExternalTaskClient#start()} is called. Running with the lowest precedence
 * guarantees this listener fires after each worker's {@code subscribe()} handler,
 * so the first poll already covers all topics.
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExternalTaskClientStarter {
    
    private final ExternalTaskClient externalTaskClient;
    
    @EventListener(ApplicationReadyEvent.class)
    @Order()
    public void start() {
        externalTaskClient.start();
        log.info("External Task Client started; polling all subscribed topics");
    }
}
