package com.team7.mobile.common.exception;

/**
 * Thrown when a requested resource does not exist (HTTP 404).
 */
public class ResourceNotFoundException extends BusinessException {

    public ResourceNotFoundException(String message) {
        super("NOT_FOUND", message, 404);
    }

    public ResourceNotFoundException(String resource, Object id) {
        super("NOT_FOUND", resource + " not found: " + id, 404);
    }
}
