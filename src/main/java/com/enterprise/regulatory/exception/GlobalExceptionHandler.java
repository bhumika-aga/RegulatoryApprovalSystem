package com.enterprise.regulatory.exception;

import com.enterprise.regulatory.dto.response.ApiResponse;
import com.enterprise.regulatory.service.ConcurrencyRetry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.ConstraintViolationException;
import org.camunda.bpm.engine.OptimisticLockingException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for the application.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(
        ResourceNotFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                   .body(ApiResponse.error(ex.getMessage(), request.getRequestURI()));
    }
    
    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<ApiResponse<Void>> handleWorkflowException(
        WorkflowException ex, HttpServletRequest request) {
        log.warn("Workflow error: {}", ex.getMessage());
        // A concurrent-update conflict that outlasted the retries is a 409, not a bad request.
        HttpStatus status = ConcurrencyRetry.isOptimisticLockFailure(ex) ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status)
                   .body(ApiResponse.error(ex.getMessage(), request.getRequestURI()));
    }
    
    @ExceptionHandler(TaskOperationException.class)
    public ResponseEntity<ApiResponse<Void>> handleTaskOperationException(
        TaskOperationException ex, HttpServletRequest request) {
        log.warn("Task operation error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                   .body(ApiResponse.error(ex.getMessage(), request.getRequestURI()));
    }
    
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
        AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                   .body(ApiResponse.error("Access denied: " + ex.getMessage(), request.getRequestURI()));
    }
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationErrors(
        MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        log.warn("Validation failed: {}", errors);
        
        ApiResponse<Map<String, String>> response = ApiResponse.<Map<String, String>>builder()
                                                        .success(false)
                                                        .message("Validation failed")
                                                        .data(errors)
                                                        .path(request.getRequestURI())
                                                        .build();
        
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
    
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
        MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = String.format("Invalid value '%s' for parameter '%s'",
            ex.getValue(), ex.getName());
        log.warn("Type mismatch: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                   .body(ApiResponse.error(message, request.getRequestURI()));
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
        IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                   .body(ApiResponse.error(ex.getMessage(), request.getRequestURI()));
    }
    
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> handleNoResourceFound(
        HttpServletRequest request) {
        // Silently return 404 for favicon and other static resource requests
        if (request.getRequestURI().contains("favicon")) {
            return ResponseEntity.notFound().build();
        }
        log.warn("Resource not found: {}", request.getRequestURI());
        return ResponseEntity.notFound().build();
    }
    
    /**
     * The request was changed concurrently (a JPA or a Camunda engine version conflict) and the
     * retries (see {@code ConcurrencyRetry}) did not resolve it; the client may simply try again.
     */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockingException.class})
    public ResponseEntity<ApiResponse<Void>> handleConcurrentUpdate(Exception ex, HttpServletRequest request) {
        log.warn("Concurrent update conflict on {} {}: {}", request.getMethod(), request.getRequestURI(),
            ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                   .body(ApiResponse.error("The request was modified concurrently; please retry",
                       request.getRequestURI()));
    }
    
    /**
     * Requests Spring MVC rejects before reaching a controller (malformed JSON, a missing
     * parameter or header, an unsupported method or media type, invalid method arguments).
     * These are the client's errors, so they get their 4xx status rather than a 500.
     */
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        ServletRequestBindingException.class,
        HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeNotSupportedException.class,
        HandlerMethodValidationException.class,
        ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleClientError(Exception ex, HttpServletRequest request) {
        HttpStatusCode status = HttpStatus.BAD_REQUEST;
        HttpHeaders headers = new HttpHeaders();
        String message = "Invalid request";
        if (ex instanceof ErrorResponse errorResponse) {
            status = errorResponse.getStatusCode();
            headers.addAll(errorResponse.getHeaders());
            if (errorResponse.getBody().getDetail() != null) {
                message = errorResponse.getBody().getDetail();
            }
        }
        if (ex instanceof HttpMessageNotReadableException) {
            // Don't echo parser internals back to the client.
            message = "Malformed or unreadable request body";
        } else if (ex instanceof ConstraintViolationException violation) {
            message = violation.getMessage();
        }
        log.warn("Client error {} on {} {}: {}", status.value(), request.getMethod(), request.getRequestURI(),
            ex.getMessage());
        return ResponseEntity.status(status).headers(headers)
                   .body(ApiResponse.error(message, request.getRequestURI()));
    }
    
    /**
     * The client accepts no media type this API can produce, so no body can be sent.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> handleNotAcceptable(HttpServletRequest request) {
        log.warn("Not acceptable: {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(
        Exception ex, HttpServletRequest request) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                   .body(ApiResponse.error("An unexpected error occurred. Please try again later.",
                       request.getRequestURI()));
    }
}
