package ru.otus.hw.dto;

import java.math.BigDecimal;

/**
 * DTO for the result of a refund operation.
 * Used for internal service-to-service communication.
 */
public record RefundResponseDto(
        Long userId,
        Long accountId,
        BigDecimal newBalance,
        boolean success,
        Long transactionId
) {
}
