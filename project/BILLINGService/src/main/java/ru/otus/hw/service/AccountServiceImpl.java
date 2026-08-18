package ru.otus.hw.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.otus.hw.dto.AccountCreateDto;
import ru.otus.hw.dto.AccountResponseDto;
import ru.otus.hw.dto.DepositResponseDto;
import ru.otus.hw.dto.RefundResponseDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.dto.WithdrawStatusDto;
import ru.otus.hw.dto.mapper.AccountMapper;
import ru.otus.hw.exception.NotFoundException;
import ru.otus.hw.models.Account;
import ru.otus.hw.repository.AccountRepository;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    private final TransactionService transactionService;

    private final AccountMapper mapper;

    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountResponseDto> findById(Long id) {
        log.debug("Looking for account with id: {}", id);
        return accountRepository.findById(id)
                .map(account -> {
                    log.info("Account with id: {} found", id);
                    return mapper.toAccountResponseDto(account);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponseDto getById(Long id) {
        return findById(id)
                .orElseThrow(() -> new NotFoundException("Account not found with id: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountResponseDto> findByUserId(Long userId) {
        log.debug("Looking for account with userId: {}", userId);
        return accountRepository.findByUserId(userId)
                .map(account -> {
                    log.info("Account for userId: {} found with account id: {}", userId, account.getId());
                    return mapper.toAccountResponseDto(account);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponseDto getByUserId(Long userId) {
        return findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Account not found for userId: " + userId));
    }

    /**
     * Идемпотентное создание аккаунта по natural key userId (unique accounts.user_id):
     * check-then-insert, а гонка параллельных вставок (например, ретраи consumer'а)
     * разруливается по образцу {@code TransactionServiceImpl.deposit} — мутация короткой
     * транзакцией через {@link TransactionTemplate}, конфликт уникального ключа ловится
     * ВНЕ её, и повторный SELECT в новой транзакции возвращает аккаунт-победитель
     * вместо проброса {@code DataIntegrityViolationException} в глобальный обработчик (409).
     */
    @Override
    public AccountResponseDto createAccount(@NonNull AccountCreateDto accountCreateDto) {
        Long userId = accountCreateDto.userId();
        if (userId == null || userId <= 0L) {
            throw new IllegalArgumentException("UserId must not be null or empty and have to be positive");
        }

        Optional<Account> existingAccount = accountRepository.findByUserId(userId);
        if (existingAccount.isPresent()) {
            log.info("Account for userId: {} already exists, returning existing account", userId);
            return mapper.toAccountResponseDto(existingAccount.get());
        }

        log.debug("Creating account for user with Id: {}", userId);
        try {
            return Objects.requireNonNull(insertNewAccount(userId));
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent account creation for userId: {} detected, returning the winner", userId);
            Account winner = accountRepository.findByUserId(userId).orElseThrow(() -> e);
            return mapper.toAccountResponseDto(winner);
        }
    }

    private AccountResponseDto insertNewAccount(Long userId) {
        return transactionTemplate.execute(_ -> {
            Account account = mapper.toEntity(new AccountCreateDto(userId));
            account.setUserId(userId);
            account.setBalance(BigDecimal.valueOf(0));

            Account savedAccount = accountRepository.saveAndFlush(account);
            log.info("Account for user with Id: {} created successfully with Id: {}", userId,
                    savedAccount.getId());
            return mapper.toAccountResponseDto(savedAccount);
        });
    }

    /** Идемпотентные операции управляют транзакциями сами (TransactionTemplate в TransactionService):
     * общая @Transactional здесь создала бы внешнюю транзакцию, в которой повторный SELECT после
     * нарушения уникального ключа невозможен (Postgres помечает транзакцию aborted).
    */
    @Override
    public DepositResponseDto depositToAccount(Long userId, BigDecimal amount, String idempotencyKey) {
        log.debug("Depositing amount {} to account for userId: {} (idempotencyKey: {})", amount, userId,
                idempotencyKey);
        DepositResponseDto depositResponseDto = transactionService.deposit(userId, amount, idempotencyKey);
        log.info("Deposit of {} to userId {} completed. TransactionId: {}", amount, userId,
                depositResponseDto.transactionId());
        return depositResponseDto;
    }

    @Override
    public WithdrawResponseDto withdrawFromAccount(Long userId, BigDecimal amount, Long orderId) {
        log.debug("Initiating withdrawal for userId: {} with amount: {}", userId, amount);
        WithdrawResponseDto withdrawResponseDto = transactionService.withdraw(userId, amount, orderId);
        log.info("Withdrawal of {} from userId {} completed. TransactionId: {}", amount, userId,
                withdrawResponseDto.transactionId());
        return withdrawResponseDto;
    }

    @Override
    public RefundResponseDto refundToAccount(Long userId, BigDecimal amount, Long orderId) {
        log.debug("Initiating refund for userId: {} with amount: {}, orderId: {}", userId, amount, orderId);
        RefundResponseDto refundResponseDto = transactionService.refund(userId, amount, orderId);
        log.info("Refund of {} to userId {} completed. TransactionId: {}", amount, userId,
                refundResponseDto.transactionId());
        return refundResponseDto;
    }

    @Override
    @Transactional(readOnly = true)
    public WithdrawStatusDto getWithdrawStatus(Long orderId) {
        log.debug("Fetching withdrawal status for orderId: {}", orderId);
        return transactionService.getWithdrawStatus(orderId);
    }
}
