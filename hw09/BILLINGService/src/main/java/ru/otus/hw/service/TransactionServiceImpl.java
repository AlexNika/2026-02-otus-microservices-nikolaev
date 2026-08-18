package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import ru.otus.hw.dto.DepositResponseDto;
import ru.otus.hw.dto.RefundResponseDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.dto.WithdrawStatusDto;
import ru.otus.hw.dto.mapper.AccountMapper;
import ru.otus.hw.exception.BillingOperationException;
import ru.otus.hw.models.Account;
import ru.otus.hw.models.Transaction;
import ru.otus.hw.repository.AccountRepository;
import ru.otus.hw.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Транзакции биллинга. Депозит идемпотентен по-опциональному idempotencyKey,
 * списание/возврат - по orderId (частичный уникальный индекс БД).
 *
 * <p>Мутационная фаза (баланс + транзакция) выполняется короткой транзакцией через
 * {@link TransactionTemplate}; конфликт уникального ключа (параллельный запрос) ловится
 * ВНЕ её - после отката повторный SELECT выполняется уже в новой транзакции и возвращает
 * replay-ответ вместо 500 (в Postgres повторный SELECT в прерванной транзакции невозможен).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private static final String UPDATING_BALANCE_MESSAGE =
            "Updating balance for account id: {} from {} to {}";

    private static final RefundResponseDto NO_OP_REFUND_RESPONSE =
            new RefundResponseDto(true, null, null, null);

    private final AccountRepository accountRepository;

    private final TransactionRepository transactionRepository;

    private final AccountMapper mapper;

    private final TransactionTemplate transactionTemplate;

    private record BalanceChange(
            BigDecimal amount,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter
    ) {
    }

    @Override
    public DepositResponseDto deposit(@NonNull Long userId, @NonNull BigDecimal amount, String idempotencyKey) {
        log.debug("Processing deposit for userId: {} with amount: {} (idempotencyKey: {})", userId, amount,
                idempotencyKey);
        validateAmount(amount, "deposit");
        boolean idempotent = StringUtils.hasText(idempotencyKey);
        if (idempotent) {
            Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return replayDeposit(existing.get(), userId, amount, idempotencyKey);
            }
        }
        try {
            DepositResponseDto response = transactionTemplate.execute(_ -> {
                Account account = getActiveAccount(userId);
                BalanceChange balanceChange = increaseBalance(account, amount);
                Transaction transaction = buildTransaction(account, Transaction.TransactionType.DEPOSIT,
                        balanceChange, null, idempotent ? idempotencyKey : null);
                Transaction savedTransaction = transactionRepository.saveAndFlush(transaction);
                log.info("Deposit of {} to userId {} completed. New balance: {}, transactionId: {}", amount, userId,
                        balanceChange.balanceAfter(), savedTransaction.getId());
                return mapper.toDepositResponseDto(savedTransaction);
            });
            return Objects.requireNonNull(response);
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent deposit with the same idempotency key detected: {}", idempotencyKey);
            Transaction winner = transactionRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return replayDeposit(winner, userId, amount, idempotencyKey);
        }
    }

    @Override
    public WithdrawResponseDto withdraw(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId) {
        log.debug("Processing withdrawal for userId: {} with amount: {} (orderId: {})", userId, amount, orderId);
        validateAmount(amount, "withdrawal");
        Optional<WithdrawResponseDto> existingWithdrawal = checkIdempotentWithdrawal(orderId, userId, amount);
        if (existingWithdrawal.isPresent()) {
            return existingWithdrawal.get();
        }
        try {
            WithdrawResponseDto response = transactionTemplate.execute(_ -> {
                Account account = getActiveAccount(userId);
                BalanceChange balanceChange = decreaseBalance(account, userId, amount);
                Transaction savedTransaction = transactionRepository.saveAndFlush(
                        buildTransaction(account, Transaction.TransactionType.WITHDRAWAL, balanceChange, orderId,
                                null));
                log.info("Withdrawal of {} from userId {} completed. New balance: {}, transactionId: {}", amount,
                        userId, balanceChange.balanceAfter(), savedTransaction.getId());
                return new WithdrawResponseDto(userId, account.getId(), balanceChange.balanceAfter(), true,
                        savedTransaction.getId());
            });
            return Objects.requireNonNull(response);
        } catch (DataIntegrityViolationException e) {
            if (orderId == null) {
                throw e;
            }
            log.info("Concurrent withdrawal for the same orderId detected: {}", orderId);
            Transaction winner = transactionRepository
                    .findByOrderIdAndTransactionType(orderId, Transaction.TransactionType.WITHDRAWAL)
                    .orElseThrow(() -> e);
            return replayWithdraw(winner, userId, amount, orderId);
        }
    }

    @Override
    @Transactional
    public RefundResponseDto refund(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId) {
        log.debug("Processing refund for userId: {} with amount: {} (orderId: {})", userId, amount, orderId);
        validateAmount(amount, "refund");
        Optional<RefundResponseDto> existingRefund = checkIdempotentRefund(orderId);
        if (existingRefund.isPresent()) {
            return existingRefund.get();
        }
        Account account = getActiveAccount(userId);
        BalanceChange balanceChange = increaseBalance(account, amount);
        Transaction savedTransaction = saveTransaction(account, Transaction.TransactionType.REFUND, balanceChange,
                orderId);
        log.info("Refund of {} to userId {} completed. New balance: {}, transactionId: {}", amount, userId,
                balanceChange.balanceAfter(), savedTransaction.getId()
        );

        return new RefundResponseDto(true, savedTransaction.getId(), balanceChange.balanceAfter(),
                null);
    }

    /**
     * Read-only статус списания по заказу для recovery сага-оркестратора: есть ли транзакция
     * WITHDRAWAL по orderId. Без побочных эффектов - безопасный повторный вызов.
     */
    @Override
    @Transactional(readOnly = true)
    public WithdrawStatusDto getWithdrawStatus(@NonNull Long orderId) {
        log.debug("Fetching withdrawal status for orderId: {}", orderId);
        return transactionRepository.findByOrderIdAndTransactionType(orderId, Transaction.TransactionType.WITHDRAWAL)
                .map(withdrawal -> {
                    log.info("Withdrawal found for orderId: {}, transactionId: {}, amount: {}", orderId,
                            withdrawal.getId(), withdrawal.getAmount());
                    return new WithdrawStatusDto(orderId, true, withdrawal.getId(), withdrawal.getAmount());
                })
                .orElseGet(() -> {
                    log.info("No withdrawal found for orderId: {}", orderId);
                    return new WithdrawStatusDto(orderId, false, null, null);
                });
    }

    /**
     * Идемпотентный replay списания со сверкой payload'а: userId и сумма должны совпадать
     * с исходной транзакцией, иначе 409 IDEMPOTENCY_KEY_CONFLICT.
     */
    private WithdrawResponseDto replayWithdraw(@NonNull Transaction existing, @NonNull Long userId,
                                               @NonNull BigDecimal amount, Long orderId) {
        Long existingUserId = existing.getAccount() != null ? existing.getAccount().getUserId() : null;
        boolean amountMatches = existing.getAmount() != null && existing.getAmount().compareTo(amount) == 0;
        if (!Objects.equals(existingUserId, userId) || !amountMatches) {
            log.warn("Withdrawal replay conflict for orderId: {}: existing userId={}, amount={}; "
                            + "requested userId={}, amount={}", orderId, existingUserId, existing.getAmount(), userId,
                    amount);
            throw BillingOperationException.idempotencyKeyConflict(userId, "orderId " + orderId);
        }
        log.info("Idempotent replay of withdrawal for orderId: {}, returning existing transactionId: {}", orderId,
                existing.getId());
        return mapper.toWithdrawResponseDto(existing);
    }

    private DepositResponseDto replayDeposit(@NonNull Transaction existing, @NonNull Long userId,
                                             @NonNull BigDecimal amount, @NonNull String idempotencyKey) {
        if (existing.getAmount() == null || existing.getAmount().compareTo(amount) != 0) {
            log.warn("Deposit idempotency key conflict: key: {}, existing amount: {}, request amount: {}",
                    idempotencyKey, existing.getAmount(), amount);
            throw BillingOperationException.idempotencyKeyConflict(userId, idempotencyKey);
        }
        log.info("Idempotent replay of deposit for key: {}, returning existing transactionId: {}", idempotencyKey,
                existing.getId());
        return mapper.toDepositResponseDto(existing);
    }

    private Optional<RefundResponseDto> checkIdempotentRefund(Long orderId) {
        if (orderId == null) {
            log.info("Refund without orderId cannot be matched to a withdrawal, refund is no-op");
            return Optional.of(NO_OP_REFUND_RESPONSE);
        }
        Optional<Transaction> withdrawal = transactionRepository
                .findByOrderIdAndTransactionType(orderId, Transaction.TransactionType.WITHDRAWAL);

        if (withdrawal.isEmpty()) {
            log.info("No withdrawal for order: {}, refund is no-op", orderId);
            return Optional.of(NO_OP_REFUND_RESPONSE);
        }
        Optional<Transaction> existingRefund = transactionRepository
                .findByOrderIdAndTransactionType(orderId, Transaction.TransactionType.REFUND);

        if (existingRefund.isPresent()) {
            Transaction existing = existingRefund.get();
            log.info("Idempotent replay of refund for orderId: {}, returning existing transactionId: {}", orderId,
                    existing.getId());
            return Optional.of(new RefundResponseDto(true, existing.getId(), existing.getBalanceAfter(),
                    null));
        }

        return Optional.empty();
    }

    private Optional<WithdrawResponseDto> checkIdempotentWithdrawal(Long orderId, Long userId, BigDecimal amount) {
        if (orderId == null) {
            return Optional.empty();
        }

        Optional<Transaction> existingWithdrawal = transactionRepository
                .findByOrderIdAndTransactionType(orderId, Transaction.TransactionType.WITHDRAWAL);

        if (existingWithdrawal.isPresent()) {
            Transaction existing = existingWithdrawal.get();
            return Optional.of(replayWithdraw(existing, userId, amount, orderId));
        }

        return Optional.empty();
    }

    private @NonNull Account getActiveAccount(@NonNull Long userId) {
        Account account = getAccount(userId);
        validateAccountActive(account, userId);
        return account;
    }

    private @NonNull BalanceChange increaseBalance(@NonNull Account account, BigDecimal amount) {
        BigDecimal oldBalance = account.getBalance();
        BigDecimal newBalance = oldBalance.add(amount);

        return applyNewBalance(account, amount, oldBalance, newBalance);
    }

    private @NonNull BalanceChange decreaseBalance(@NonNull Account account, Long userId, BigDecimal amount) {
        BigDecimal oldBalance = account.getBalance();

        if (oldBalance.compareTo(amount) < 0) {
            log.warn("Insufficient balance: {} for withdrawal of {} by userId: {}", oldBalance, amount, userId);

            throw BillingOperationException.insufficientFunds(userId, oldBalance, amount);
        }

        BigDecimal newBalance = oldBalance.subtract(amount);

        return applyNewBalance(account, amount, oldBalance, newBalance);
    }

    private BalanceChange applyNewBalance(Account account, BigDecimal amount,
                                          BigDecimal oldBalance, BigDecimal newBalance) {
        logUpdatedBalance(account, oldBalance, newBalance);

        account.setBalance(newBalance);
        accountRepository.save(account);

        return new BalanceChange(amount, oldBalance, newBalance);
    }

    private @NonNull Transaction saveTransaction(Account account, Transaction.TransactionType type,
                                                 BalanceChange balanceChange, Long orderId) {
        return transactionRepository.save(buildTransaction(account, type, balanceChange, orderId, null));
    }

    private Transaction buildTransaction(Account account, Transaction.TransactionType type,
                                         @NonNull BalanceChange balanceChange, Long orderId, String idempotencyKey) {
        return Transaction.builder()
                .account(account)
                .transactionType(type)
                .amount(balanceChange.amount())
                .balanceBefore(balanceChange.balanceBefore())
                .balanceAfter(balanceChange.balanceAfter())
                .orderId(orderId)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private @NonNull Account getAccount(@NonNull Long userId) {
        return accountRepository.findByUserId(userId).orElseThrow(() -> {
            log.warn("Account not found for userId: {}", userId);
            return BillingOperationException.accountNotFound(userId);
        });
    }

    private static void validateAmount(BigDecimal amount, String operation) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid {} amount: {} (must be > 0)", operation, amount);
            throw BillingOperationException.invalidAmount(amount);
        }
    }

    private static void validateAccountActive(@NonNull Account account, Long userId) {
        if (!account.isEnabled() || account.isLocked()) {
            log.warn("Account for userId: {} is inactive (enabled={}, locked={})", userId, account.isEnabled(),
                    account.isLocked());
            throw BillingOperationException.accountInactive(userId);
        }
    }

    private static void logUpdatedBalance(@NonNull Account account, BigDecimal oldBalance, BigDecimal newBalance) {
        log.debug(UPDATING_BALANCE_MESSAGE, account.getId(), oldBalance, newBalance);
    }
}
