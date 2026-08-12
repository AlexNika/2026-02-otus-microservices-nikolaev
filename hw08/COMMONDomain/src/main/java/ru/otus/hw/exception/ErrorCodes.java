package ru.otus.hw.exception;

public final class ErrorCodes {

    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";

    public static final String DELIVERY_NO_FREE_COURIER = "DELIVERY_NO_FREE_COURIER";

    public static final String DELIVERY_CAPACITY_CONFLICT = "DELIVERY_CAPACITY_CONFLICT";

    public static final String DELIVERY_RESERVATION_STATE_CONFLICT = "DELIVERY_RESERVATION_STATE_CONFLICT";

    public static final String DELIVERY_COURIER_ASSIGNMENT_FAILED = "DELIVERY_COURIER_ASSIGNMENT_FAILED";

    public static final String BILLING_INSUFFICIENT_FUNDS = "BILLING_INSUFFICIENT_FUNDS";

    public static final String BILLING_ACCOUNT_NOT_FOUND = "BILLING_ACCOUNT_NOT_FOUND";

    public static final String BILLING_INVALID_AMOUNT = "BILLING_INVALID_AMOUNT";

    public static final String BILLING_ACCOUNT_INACTIVE = "BILLING_ACCOUNT_INACTIVE";

    public static final String ORDER_STATE_CONFLICT = "ORDER_STATE_CONFLICT";

    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";

    public static final String DATA_INTEGRITY_VIOLATION = "DATA_INTEGRITY_VIOLATION";

    public static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";

    private ErrorCodes() {
    }
}
