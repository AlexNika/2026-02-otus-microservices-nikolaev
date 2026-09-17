package ru.otus.hw.exception;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class BillingOperationException extends RuntimeException {

    private final String code;

    private final Long userId;

    private final BigDecimal balance;

    private final BigDecimal requiredAmount;

    private BillingOperationException(String code, Long userId, BigDecimal balance,
                                    BigDecimal requiredAmount, String message) {
        super(message);
        this.code = code;
        this.userId = userId;
        this.balance = balance;
        this.requiredAmount = requiredAmount;
    }

    public static BillingOperationException insufficientFunds(Long userId, BigDecimal balance,
                                                             BigDecimal requiredAmount) {
        String message = "Insufficient funds for userId: " + userId
                + ", balance: " + balance
                + ", required: " + requiredAmount;
        return new BillingOperationException(ErrorCodes.BILLING_INSUFFICIENT_FUNDS, userId, balance,
                requiredAmount, message);
    }

    public static BillingOperationException accountNotFound(Long userId) {
        String message = "Account not found for userId: " + userId;
        return new BillingOperationException(ErrorCodes.BILLING_ACCOUNT_NOT_FOUND, userId, null, null, message);
    }

    public static BillingOperationException accountInactive(Long userId) {
        String message = "Account is inactive (disabled or locked) for userId: " + userId;
        return new BillingOperationException(ErrorCodes.BILLING_ACCOUNT_INACTIVE, userId, null, null, message);
    }

    public static BillingOperationException invalidAmount(BigDecimal amount) {
        String message = "Invalid amount: " + amount + " (must be greater than 0)";
        return new BillingOperationException(ErrorCodes.BILLING_INVALID_AMOUNT, null, null, null, message);
    }

    /**
     * Конфликт ключа идемпотентности: ключ (idempotencyKey депозита или orderId списания)
     * уже использован с другими параметрами (сумма/владелец).
     */
    public static BillingOperationException idempotencyKeyConflict(Long userId, String idempotencyKey) {
        String message = "Idempotency key " + idempotencyKey
                + " already used with different parameters for userId: " + userId;
        return new BillingOperationException(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT, userId, null, null, message);
    }
}
