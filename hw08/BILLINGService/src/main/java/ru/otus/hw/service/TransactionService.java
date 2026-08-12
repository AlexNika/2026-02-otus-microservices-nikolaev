package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import ru.otus.hw.dto.DepositResponseDto;
import ru.otus.hw.dto.RefundResponseDto;
import ru.otus.hw.dto.WithdrawResponseDto;

import java.math.BigDecimal;

public interface TransactionService {

    DepositResponseDto deposit(@NonNull Long userId, @NonNull BigDecimal amount);

    WithdrawResponseDto withdraw(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId);

    RefundResponseDto refund(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId);
}
