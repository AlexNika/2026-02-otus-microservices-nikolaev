package ru.otus.hw.exception;

public class BillingServiceException extends RuntimeException {
    public BillingServiceException(String message) {
        super(message);
    }

    public BillingServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
