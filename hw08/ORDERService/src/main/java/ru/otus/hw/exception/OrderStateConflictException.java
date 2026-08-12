package ru.otus.hw.exception;

import lombok.Getter;

@Getter
public class OrderStateConflictException extends RuntimeException {

    private final String code;

    private OrderStateConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static OrderStateConflictException cancelNotAllowed(Long orderId, String currentStatus) {
        String message = "Only PLACED orders can be canceled, current status: " + currentStatus;
        return new OrderStateConflictException(ErrorCodes.ORDER_STATE_CONFLICT, message);
    }

    public static OrderStateConflictException resourceNotCancellable(Long orderId, String resource, String reason) {
        String message = "Order " + orderId + " cannot be canceled: reservation in " + resource
                + " is final and cannot be released: " + reason;
        return new OrderStateConflictException(ErrorCodes.ORDER_STATE_CONFLICT, message);
    }
}
