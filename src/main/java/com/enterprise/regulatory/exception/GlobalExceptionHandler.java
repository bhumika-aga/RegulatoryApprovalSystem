package com.enterprise.regulatory.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.enterprise.regulatory.dto.response.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler for the application.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

        @ExceptionHandler(ResourceNotFoundException.class)
        public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(
                        ResourceNotFoundException ex, HttpServletRequest request) {
                log.error("Resource not found: {}", ex.getMessage());
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(ApiResponse.error(ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler(WorkflowException.class)
        public ResponseEntity<ApiResponse<Void>> handleWorkflowException(
                        WorkflowException ex, HttpServletRequest request) {
                log.error("Workflow error: {}", ex.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(ApiResponse.error(ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler(TaskOperationException.class)
        public ResponseEntity<ApiResponse<Void>> handleTaskOperationException(
                        TaskOperationException ex, HttpServletRequest request) {
                log.error("Task operation error: {}", ex.getMessage());
                return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(ApiResponse.error(ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler(AccessDeniedException.class)
        public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
                        AccessDeniedException ex, HttpServletRequest request) {
                log.error("Access denied: {}", ex.getMessage());
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

                log.error("Validation failed: {}", errors);

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
                log.error("Type mismatch: {}", message);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(ApiResponse.error(message, request.getRequestURI()));
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(
                        IllegalArgumentException ex, HttpServletRequest request) {
                log.error("Illegal argument: {}", ex.getMessage());
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(ApiResponse.error(ex.getMessage(), request.getRequestURI()));
        }

        @ExceptionHandler(NoResourceFoundException.class)
        public ResponseEntity<Void> handleNoResourceFound(
                        NoResourceFoundException ex, HttpServletRequest request) {
                // Silently return 404 for favicon and other static resource requests
                if (request.getRequestURI().contains("favicon")) {
                        return ResponseEntity.notFound().build();
                }
                log.warn("Resource not found: {}", request.getRequestURI());
                return ResponseEntity.notFound().build();
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
