package com.team7.mobile.common.exception;

/**
 * Thrown when the user is not authenticated or the token is invalid (HTTP 401).
 */
public class UnauthorizedException extends BusinessException {

    public UnauthorizedException(String message) {
        super("UNAUTHORIZED", message, 401);
    }
}
