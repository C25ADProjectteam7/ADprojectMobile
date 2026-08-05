package com.team7.mobile.common.exception;

/**
 * Base business exception — carries an error code and a user-readable message.
 * Mapped to HTTP responses by GlobalExceptionHandler.
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final int httpStatus;

    public BusinessException(String errorCode, String message) {
        this(errorCode, message, 400);
    }

    public BusinessException(String errorCode, String message, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public String getErrorCode() { return errorCode; }
    public int getHttpStatus() { return httpStatus; }
}
