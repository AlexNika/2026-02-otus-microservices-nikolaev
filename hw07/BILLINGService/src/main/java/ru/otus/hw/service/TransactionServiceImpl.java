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
import ru.otus.hw.exception.InsufficientFundsException;
import ru.otus.hw.exception.InvalidDepositException;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.models.Account;
import ru.otus.hw.models.Transaction;
import ru.otus.hw.repository.AccountRepository;
import ru.otus.hw.repository.TransactionRepository;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final static String NOTFOUND_MESSAGE = "Account not found for userId: ";

    private final static String UPDATING_BALANCE_MESSAGE = "Updating balance for account id: {} from {} to {}";

    private final AccountRepository accountRepository;

    private final TransactionRepository transactionRepository;

    private final AccountMapper mapper;

    @Override
    @Transactional
    public DepositResponseDto deposit(@NonNull Long userId, @NonNull BigDecimal amount) {
        log.debug("Processing deposit for userId: {} with amount: {}", userId, amount);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid deposit amount: {} for userId: {} (must be > 0)", amount, userId);
            throw new InvalidDepositException("Amount must be greater than 0");
        }

        Account account = getAccount(userId);
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
        log.info("Deposit of {} to userId {} completed. New balance: {}, transactionId: {}", amount, userId, newBalance, savedTransaction.getId());

        return mapper.toDepositResponseDto(savedTransaction);
    }

    @Override
    @Transactional
    public WithdrawResponseDto withdraw(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId) {
        log.debug("Processing withdrawal for userId: {} with amount: {} (orderId: {})", userId, amount, orderId);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid withdrawal amount: {} for userId: {} (must be > 0)", amount, userId);
            throw new InvalidDepositException("Amount must be greater than 0");
        }

        Account account = getAccount(userId);

        BigDecimal oldBalance = account.getBalance();
        BigDecimal newBalance = oldBalance.subtract(amount);
        logUpdatedBalance(account, oldBalance, newBalance);

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("Insufficient balance: {} for withdrawal of {} by userId: {}", oldBalance, amount, userId);
            throw new InsufficientFundsException("Insufficient funds: current balance " + oldBalance + ", required " + amount);
        }

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
        log.info("Withdrawal of {} from userId {} completed. New balance: {}, transactionId: {}", amount, userId, newBalance, savedTransaction.getId());

        return new WithdrawResponseDto(userId, account.getId(), newBalance, true, savedTransaction.getId());
    }

    @Override
    @Transactional
    public RefundResponseDto refund(@NonNull Long userId, @NonNull BigDecimal amount, Long orderId) {
        log.debug("Processing refund for userId: {} with amount: {} (orderId: {})", userId, amount, orderId);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid refund amount: {} for userId: {} (must be > 0)", amount, userId);
            throw new InvalidDepositException("Amount must be greater than 0");
        }

        Account account = getAccount(userId);

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

        return new RefundResponseDto(userId, account.getId(), newBalance, true, savedTransaction.getId());
    }

    private @NonNull Account getAccount(@NonNull Long userId) {
        return accountRepository.findByUserId(userId).orElseThrow(() -> {
            String message = NOTFOUND_MESSAGE + userId;
            log.warn(message);
            return new NotFoundException(message);
        });
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
