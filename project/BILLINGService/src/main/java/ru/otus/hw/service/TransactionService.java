package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import ru.otus.hw.dto.DepositResponseDto;
import ru.otus.hw.dto.RefundResponseDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.dto.WithdrawStatusDto;

import java.math.BigDecimal;

public interface TransactionService {

    DepositResponseDto deposit(@NonNull Long userId, @NonNull BigDecimal amount, String idempotencyKey);

    WithdrawResponseDto withdraw(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId);

    RefundResponseDto refund(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId);

    /**
     * Read-only статус списания по заказу: наличие транзакции WITHDRAWAL без побочных эффектов.
     */
    WithdrawStatusDto getWithdrawStatus(@NonNull Long orderId);
}
