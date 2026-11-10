package com.enterprise.regulatory.service;

import com.enterprise.regulatory.model.entity.RegulatoryRequest;
import com.enterprise.regulatory.repository.RegulatoryRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Applies a change to a regulatory request in one transaction.
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

    /**
     * Loads the request, applies {@code change} and saves it, all in one transaction.
     *
     * @return {@code false} if {@code requestId} is missing, malformed or unknown
     */
    @Transactional
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
        return requestRepository.findById(id)
                   .map(request -> {
                       change.accept(request);
                       requestRepository.save(request);
                       return true;
                   })
                   .orElseGet(() -> {
                       log.warn("No regulatory request found for id {}", requestId);
                       return false;
                   });
    }
}
