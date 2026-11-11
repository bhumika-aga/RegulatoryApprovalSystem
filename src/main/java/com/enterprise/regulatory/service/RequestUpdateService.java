package com.enterprise.regulatory.service;

import com.enterprise.regulatory.model.entity.RegulatoryRequest;
import com.enterprise.regulatory.repository.RegulatoryRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Applies a change to a regulatory request in one transaction, retried from a fresh read if
 * another transaction updated the request concurrently (see {@link ConcurrencyRetry}).
 *
 * <p>The external task workers use this rather than annotating their own handlers with
 * {@code @Transactional}: the task client calls a handler directly, not through Spring's
 * proxy, so such an annotation would have no effect.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RequestUpdateService {
    
    private final RegulatoryRequestRepository requestRepository;
    private final TransactionTemplate transactionTemplate;
    private final ConcurrencyRetry concurrencyRetry;
    
    /**
     * Loads the request, applies {@code change} and saves it, all in one transaction. On a
     * concurrent update the whole step is repeated, so {@code change} may run more than once.
     *
     * @return {@code false} if {@code requestId} is missing, malformed or unknown
     */
    public boolean update(String requestId, Consumer<RegulatoryRequest> change) {
        if (requestId == null) {
            log.warn("No requestId provided for request update");
            return false;
        }
        UUID id;
        try {
            id = UUID.fromString(requestId);
        } catch (IllegalArgumentException e) {
            log.warn("Could not parse requestId as UUID: {}", requestId);
            return false;
        }
        boolean found = concurrencyRetry.call(() -> Boolean.TRUE.equals(transactionTemplate.execute(
            status -> requestRepository.findById(id)
                          .map(request -> {
                              change.accept(request);
                              requestRepository.save(request);
                              return true;
                          })
                          .orElse(false))));
        if (!found) {
            log.warn("No regulatory request found for id {}", requestId);
        }
        return found;
    }
}
