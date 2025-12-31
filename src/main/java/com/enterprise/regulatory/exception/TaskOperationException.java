package com.enterprise.regulatory.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class TaskOperationException extends RuntimeException {

    public TaskOperationException(String message) {
        super(message);
    }

    public TaskOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
