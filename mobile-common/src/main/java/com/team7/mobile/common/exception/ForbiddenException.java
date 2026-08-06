package com.team7.mobile.common.exception;

/**
 * Thrown when the user's role has no permission for the operation (HTTP 403).
 */
public class ForbiddenException extends BusinessException {

    public ForbiddenException(String message) {
        super("FORBIDDEN", message, 403);
    }
}
