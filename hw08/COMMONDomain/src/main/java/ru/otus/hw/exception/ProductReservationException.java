package ru.otus.hw.exception;

import lombok.Getter;

@Getter
public class ProductReservationException extends RuntimeException {

    private final String code;

    private final Long productId;

    private final Integer requestedQuantity;

    private final Integer availableQuantity;

    public ProductReservationException(String code, String message) {
        this(code, null, null, null, message, null);
    }

    public ProductReservationException(String code, String message, Throwable cause) {
        this(code, null, null, null, message, cause);
    }

    private ProductReservationException(String code, Long productId, Integer requestedQuantity,
                                        Integer availableQuantity, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.productId = productId;
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public static ProductReservationException insufficientStock(Long productId, Integer requestedQuantity,
                                                                Integer availableQuantity) {
        String message = "Not enough stock for product id: " + productId
                + ", requested: " + requestedQuantity
                + ", available: " + availableQuantity;
        return new ProductReservationException(ErrorCodes.INSUFFICIENT_STOCK, productId, requestedQuantity,
                availableQuantity, message, null);
    }

}
