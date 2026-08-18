package ru.otus.hw.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BillingOperationExceptionTest {

    @Test
    @DisplayName("фабрика insufficientFunds должна заполнять код, userId, баланс, требуемую сумму и сообщение")
    void shouldBuildInsufficientFundsException() {
        BillingOperationException ex = BillingOperationException.insufficientFunds(1L,
                new BigDecimal("100.00"), new BigDecimal("1500.00"));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_INSUFFICIENT_FUNDS);
        assertThat(ex.getUserId()).isEqualTo(1L);
        assertThat(ex.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(ex.getRequiredAmount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(ex.getMessage())
                .isEqualTo("Insufficient funds for userId: 1, balance: 100.00, required: 1500.00");
    }

    @Test
    @DisplayName("фабрика accountNotFound должна заполнять код, userId и сообщение")
    void shouldBuildAccountNotFoundException() {
        BillingOperationException ex = BillingOperationException.accountNotFound(2L);

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_NOT_FOUND);
        assertThat(ex.getUserId()).isEqualTo(2L);
        assertThat(ex.getBalance()).isNull();
        assertThat(ex.getRequiredAmount()).isNull();
        assertThat(ex.getMessage()).isEqualTo("Account not found for userId: 2");
    }

    @Test
    @DisplayName("фабрика accountInactive должна заполнять код, userId и сообщение")
    void shouldBuildAccountInactiveException() {
        BillingOperationException ex = BillingOperationException.accountInactive(3L);

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_INACTIVE);
        assertThat(ex.getUserId()).isEqualTo(3L);
        assertThat(ex.getMessage()).isEqualTo("Account is inactive (disabled or locked) for userId: 3");
    }

    @Test
    @DisplayName("фабрика invalidAmount должна заполнять код и сообщение")
    void shouldBuildInvalidAmountException() {
        BillingOperationException ex = BillingOperationException.invalidAmount(new BigDecimal("-5.00"));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_INVALID_AMOUNT);
        assertThat(ex.getUserId()).isNull();
        assertThat(ex.getMessage()).isEqualTo("Invalid amount: -5.00 (must be greater than 0)");
    }
}
