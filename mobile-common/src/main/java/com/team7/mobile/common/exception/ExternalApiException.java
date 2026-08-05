package com.team7.mobile.common.exception;

/**
 * Thrown when an external API call fails (Agent/ML service, Amadeus, Google Places).
 * Mapped to HTTP 502 Bad Gateway.
 */
public class ExternalApiException extends BusinessException {

    public ExternalApiException(String apiName, String message) {
        super("EXTERNAL_API_ERROR", apiName + " call failed: " + message, 502);
    }

    public ExternalApiException(String apiName, String message, Throwable cause) {
        super("EXTERNAL_API_ERROR", apiName + " call failed: " + message, 502);
        initCause(cause);
    }
}
