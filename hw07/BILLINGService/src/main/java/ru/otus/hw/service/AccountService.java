package ru.otus.hw.service;

import ru.otus.hw.dto.*;

import java.math.BigDecimal;
import java.util.Optional;

public interface AccountService {

    Optional<AccountResponseDto> findById(Long id);

    AccountResponseDto getById(Long id);

    Optional<AccountResponseDto> findByUserId(Long userId);

    AccountResponseDto getByUserId(Long userId);

    AccountResponseDto createAccount(AccountCreateDto accountCreateDto);

    DepositResponseDto depositToAccount(Long userId, BigDecimal amount);

    WithdrawResponseDto withdrawFromAccount(Long userId, BigDecimal amount, Long orderId);

    RefundResponseDto refundToAccount(Long userId, BigDecimal amount, Long orderId);
}
