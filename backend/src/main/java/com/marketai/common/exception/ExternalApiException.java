package com.marketai.common.exception;

public class ExternalApiException extends RuntimeException {
    public ExternalApiException(String service, String message) {
        super(String.format("[%s] %s", service, message));
    }
}
