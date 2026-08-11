package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.DepositResponseDto;
import ru.otus.hw.dto.RefundResponseDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.dto.mapper.AccountMapper;
import ru.otus.hw.exception.BillingOperationException;
import ru.otus.hw.models.Account;
import ru.otus.hw.models.Transaction;
import ru.otus.hw.repository.AccountRepository;
import ru.otus.hw.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private static final String UPDATING_BALANCE_MESSAGE = "Updating balance for account id: {} from {} to {}";

    private final AccountRepository accountRepository;

    private final TransactionRepository transactionRepository;

    private final AccountMapper mapper;

    @Override
    @Transactional
    public DepositResponseDto deposit(@NonNull Long userId, @NonNull BigDecimal amount) {
        log.debug("Processing deposit for userId: {} with amount: {}", userId, amount);
        validateAmount(amount, "deposit");

        Account account = getAccount(userId);
        validateAccountActive(account, userId);

        BigDecimal oldBalance = account.getBalance();
        BigDecimal newBalance = oldBalance.add(amount);
        logUpdatedBalance(account, oldBalance, newBalance);

        account.setBalance(newBalance);
        accountRepository.save(account);

        Transaction transaction = buildTransaction(
                account,
                Transaction.TransactionType.DEPOSIT,
                amount,
                oldBalance,
                newBalance,
                null
        );

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Deposit of {} to userId {} completed. New balance: {}, transactionId: {}", amount, userId, newBalance,
                savedTransaction.getId());

        return mapper.toDepositResponseDto(savedTransaction);
    }

    @Override
    @Transactional
    public WithdrawResponseDto withdraw(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId) {
        log.debug("Processing withdrawal for userId: {} with amount: {} (orderId: {})", userId, amount, orderId);
        validateAmount(amount, "withdrawal");

        if (orderId != null) {
            Optional<Transaction> existingWithdrawal = transactionRepository
                    .findByOrderIdAndTransactionType(orderId, Transaction.TransactionType.WITHDRAWAL);
            if (existingWithdrawal.isPresent()) {
                Transaction existing = existingWithdrawal.get();
                log.info("Idempotent replay of withdrawal for orderId: {}, returning existing transactionId: {}",
                        orderId, existing.getId());
                return mapper.toWithdrawResponseDto(existing);
            }
        }

        Account account = getAccount(userId);
        validateAccountActive(account, userId);

        BigDecimal oldBalance = account.getBalance();
        if (oldBalance.compareTo(amount) < 0) {
            log.warn("Insufficient balance: {} for withdrawal of {} by userId: {}", oldBalance, amount, userId);
            throw BillingOperationException.insufficientFunds(userId, oldBalance, amount);
        }
        BigDecimal newBalance = oldBalance.subtract(amount);
        logUpdatedBalance(account, oldBalance, newBalance);

        account.setBalance(newBalance);
        accountRepository.save(account);

        Transaction transaction = buildTransaction(
                account,
                Transaction.TransactionType.WITHDRAWAL,
                amount,
                oldBalance,
                newBalance,
                orderId
        );

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Withdrawal of {} from userId {} completed. New balance: {}, transactionId: {}", amount, userId,
                newBalance, savedTransaction.getId());

        return new WithdrawResponseDto(userId, account.getId(), newBalance, true, savedTransaction.getId());
    }

    @Override
    @Transactional
    public RefundResponseDto refund(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId) {
        log.debug("Processing refund for userId: {} with amount: {} (orderId: {})", userId, amount, orderId);
        validateAmount(amount, "refund");

        if (orderId == null) {
            log.info("Refund without orderId cannot be matched to a withdrawal, refund is no-op");
            return new RefundResponseDto(true, null, null, null);
        }

        Optional<Transaction> withdrawal = transactionRepository
                .findByOrderIdAndTransactionType(orderId, Transaction.TransactionType.WITHDRAWAL);
        if (withdrawal.isEmpty()) {
            log.info("No withdrawal for order: {}, refund is no-op", orderId);
            return new RefundResponseDto(true, null, null, null);
        }

        Optional<Transaction> existingRefund = transactionRepository
                .findByOrderIdAndTransactionType(orderId, Transaction.TransactionType.REFUND);
        if (existingRefund.isPresent()) {
            Transaction existing = existingRefund.get();
            log.info("Idempotent replay of refund for orderId: {}, returning existing transactionId: {}",
                    orderId, existing.getId());
            return new RefundResponseDto(true, existing.getId(), existing.getBalanceAfter(), null);
        }

        Account account = getAccount(userId);
        validateAccountActive(account, userId);

        BigDecimal oldBalance = account.getBalance();
        BigDecimal newBalance = oldBalance.add(amount);
        logUpdatedBalance(account, oldBalance, newBalance);

        account.setBalance(newBalance);
        accountRepository.save(account);

        Transaction transaction = buildTransaction(
                account,
                Transaction.TransactionType.REFUND,
                amount,
                oldBalance,
                newBalance,
                orderId
        );

        Transaction savedTransaction = transactionRepository.save(transaction);

        log.info("Refund of {} to userId {} completed. New balance: {}, transactionId: {}", amount, userId, newBalance,
                savedTransaction.getId());

        return new RefundResponseDto(true, savedTransaction.getId(), newBalance, null);
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

    private static void validateAccountActive(Account account, Long userId) {
        if (!account.isEnabled() || account.isLocked()) {
            log.warn("Account for userId: {} is inactive (enabled={}, locked={})",
                    userId, account.isEnabled(), account.isLocked());
            throw BillingOperationException.accountInactive(userId);
        }
    }

    private Transaction buildTransaction(Account account, Transaction.TransactionType type, BigDecimal amount,
                                         BigDecimal balanceBefore, BigDecimal balanceAfter, Long orderId) {
        return Transaction.builder()
                .account(account)
                .transactionType(type)
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .orderId(orderId).build();
    }

    private static void logUpdatedBalance(@NonNull Account account, BigDecimal oldBalance, BigDecimal newBalance) {
        log.debug(UPDATING_BALANCE_MESSAGE, account.getId(), oldBalance, newBalance);
    }

}
