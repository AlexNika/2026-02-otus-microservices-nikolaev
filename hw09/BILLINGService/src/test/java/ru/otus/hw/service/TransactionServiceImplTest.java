package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import ru.otus.hw.dto.DepositResponseDto;
import ru.otus.hw.dto.RefundResponseDto;
import ru.otus.hw.dto.WithdrawResponseDto;
import ru.otus.hw.dto.WithdrawStatusDto;
import ru.otus.hw.dto.mapper.AccountMapper;
import ru.otus.hw.exception.BillingOperationException;
import ru.otus.hw.exception.ErrorCodes;
import ru.otus.hw.models.Account;
import ru.otus.hw.models.Transaction;
import ru.otus.hw.repository.AccountRepository;
import ru.otus.hw.repository.TransactionRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionServiceImplTest {

    private static final Long USER_ID = 1L;

    private static final Long ACCOUNT_ID = 10L;

    private static final Long ORDER_ID = 100L;

    private static final Long WITHDRAWAL_TX_ID = 500L;

    private static final Long REFUND_TX_ID = 501L;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("5000.00");

    private static final BigDecimal AMOUNT = new BigDecimal("1500.00");

    private static final String DEPOSIT_KEY = "dep-7f3a9c2e-1b4d-4e8a-9c5f-2d6b8a0e3f17";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountMapper mapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private TransactionServiceImpl transactionService;

    /**
     * Транзакционный шаблон в тестах сразу исполняет колбэк, как это делает реальная транзакция
     * (без реального commit/rollback).
     */
    @BeforeEach
    void setUpTransactionTemplate() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    private static @NonNull Account activeAccount(BigDecimal balance) {
        Account account = Account.builder()
                .userId(USER_ID)
                .balance(balance)
                .enabled(true)
                .locked(false)
                .build();
        account.setId(ACCOUNT_ID);
        return account;
    }

    private static @NonNull Transaction transaction(Long id, Transaction.TransactionType type, BigDecimal amount,
                                                    BigDecimal balanceBefore, BigDecimal balanceAfter) {
        Transaction transaction = Transaction.builder()
                .account(activeAccount(balanceAfter))
                .transactionType(type)
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .orderId(ORDER_ID)
                .build();
        transaction.setId(id);
        return transaction;
    }

    @Test
    @DisplayName("withdraw: должен списать средства, " +
            "сохранить транзакцию WITHDRAWAL с orderId и вернуть корректный ответ")
    void shouldWithdrawAndSaveWithdrawalTransaction() {
        Account account = activeAccount(INITIAL_BALANCE);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> {
                    Transaction saved = invocation.getArgument(0);
                    saved.setId(WITHDRAWAL_TX_ID);
                    return saved;
                });

        WithdrawResponseDto response = transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID);

        assertThat(response).isEqualTo(new WithdrawResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("3500.00"), true, WITHDRAWAL_TX_ID));

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(transactionCaptor.capture());
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionType()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);
        assertThat(savedTransaction.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(savedTransaction.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(savedTransaction.getBalanceBefore()).isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(savedTransaction.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("3500.00"));
        assertThat(savedTransaction.getAccount()).isSameAs(account);

        verify(accountRepository).save(account);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("3500.00"));
    }

    @Test
    @DisplayName("withdraw: при идемпотентном повторе должен вернуть прежний результат без повторного списания")
    void shouldReturnPreviousResultOnIdempotentWithdrawReplay() {
        Transaction existingWithdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        WithdrawResponseDto previousResult = new WithdrawResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("3500.00"), true, WITHDRAWAL_TX_ID);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(existingWithdrawal));
        when(mapper.toWithdrawResponseDto(existingWithdrawal)).thenReturn(previousResult);

        WithdrawResponseDto response = transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID);

        assertThat(response).isSameAs(previousResult);
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("withdraw: при недостатке средств должен бросить BillingOperationException " +
            "с кодом BILLING_INSUFFICIENT_FUNDS")
    void shouldThrowInsufficientFundsOnWithdraw() {
        Account account = activeAccount(new BigDecimal("100.00"));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_INSUFFICIENT_FUNDS);
        assertThat(ex.getUserId()).isEqualTo(USER_ID);
        assertThat(ex.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(ex.getRequiredAmount()).isEqualByComparingTo(AMOUNT);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("withdraw: при заблокированном счёте должен бросить BillingOperationException " +
            "с кодом BILLING_ACCOUNT_INACTIVE")
    void shouldThrowAccountInactiveWhenLockedOnWithdraw() {
        Account account = activeAccount(INITIAL_BALANCE);
        account.setLocked(true);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_INACTIVE);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("withdraw: при отключённом счёте должен бросить BillingOperationException " +
            "с кодом BILLING_ACCOUNT_INACTIVE")
    void shouldThrowAccountInactiveWhenDisabledOnWithdraw() {
        Account account = activeAccount(INITIAL_BALANCE);
        account.setEnabled(false);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_INACTIVE);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("withdraw: при отсутствии счёта должен бросить BillingOperationException " +
            "с кодом BILLING_ACCOUNT_NOT_FOUND")
    void shouldThrowAccountNotFoundOnWithdraw() {
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_NOT_FOUND);
        assertThat(ex.getUserId()).isEqualTo(USER_ID);
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("withdraw/refund: при невалидной сумме должно бросить BillingOperationException " +
            "с кодом BILLING_INVALID_AMOUNT")
    void shouldThrowInvalidAmountOnWithdrawAndRefund() {
        BillingOperationException withdrawEx = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, BigDecimal.ZERO, ORDER_ID));
        BillingOperationException refundEx = assertThrows(BillingOperationException.class,
                () -> transactionService.refund(USER_ID, AMOUNT.negate(), ORDER_ID));

        assertThat(withdrawEx.getCode()).isEqualTo(ErrorCodes.BILLING_INVALID_AMOUNT);
        assertThat(refundEx.getCode()).isEqualTo(ErrorCodes.BILLING_INVALID_AMOUNT);
        verify(accountRepository, never()).findByUserId(any());
        verify(transactionRepository, never()).save(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("refund: должен вернуть деньги, сохранить транзакцию REFUND и ответить success=true")
    void shouldRefundAndSaveRefundTransaction() {
        Account account = activeAccount(new BigDecimal("3500.00"));
        Transaction withdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(withdrawal));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.REFUND))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> {
                    Transaction saved = invocation.getArgument(0);
                    saved.setId(REFUND_TX_ID);
                    return saved;
                });

        RefundResponseDto response = transactionService.refund(USER_ID, AMOUNT, ORDER_ID);

        assertThat(response).isEqualTo(new RefundResponseDto(true, REFUND_TX_ID, new BigDecimal("5000.00"),
                null));

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionType()).isEqualTo(Transaction.TransactionType.REFUND);
        assertThat(savedTransaction.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(savedTransaction.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(savedTransaction.getBalanceAfter()).isEqualByComparingTo(INITIAL_BALANCE);

        verify(accountRepository).save(account);
        assertThat(account.getBalance()).isEqualByComparingTo(INITIAL_BALANCE);
    }

    @Test
    @DisplayName("refund: при отсутствии списания по orderId должен вернуть мягкий no-op " +
            "без изменения баланса и сохранений")
    void shouldReturnNoOpWhenNoWithdrawalForRefund() {
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());

        RefundResponseDto response = transactionService.refund(USER_ID, AMOUNT, ORDER_ID);

        assertThat(response.success()).isTrue();
        assertThat(response.errorMessage()).isNull();
        assertThat(response.transactionId()).isNull();
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("refund: при идемпотентном повторе должен вернуть прежний результат без повторного начисления")
    void shouldReturnPreviousResultOnIdempotentRefundReplay() {
        Transaction withdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        Transaction existingRefund = transaction(REFUND_TX_ID, Transaction.TransactionType.REFUND,
                AMOUNT, new BigDecimal("3500.00"), INITIAL_BALANCE);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(withdrawal));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.REFUND))
                .thenReturn(Optional.of(existingRefund));

        RefundResponseDto response = transactionService.refund(USER_ID, AMOUNT, ORDER_ID);

        assertThat(response).isEqualTo(new RefundResponseDto(true, REFUND_TX_ID, INITIAL_BALANCE, null));
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("refund: при заблокированном счёте после списания должен бросить BillingOperationException " +
            "с кодом BILLING_ACCOUNT_INACTIVE")
    void shouldThrowAccountInactiveOnRefund() {
        Account account = activeAccount(new BigDecimal("3500.00"));
        account.setLocked(true);
        Transaction withdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(withdrawal));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.REFUND))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.refund(USER_ID, AMOUNT, ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_INACTIVE);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit: при успешном пополнении должен увеличить баланс и сохранить транзакцию DEPOSIT без orderId")
    void shouldDepositSuccessfully() {
        Account account = activeAccount(INITIAL_BALANCE);
        DepositResponseDto expectedResponse = new DepositResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("6000.00"), 300L);
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> {
                    Transaction saved = invocation.getArgument(0);
                    saved.setId(300L);
                    return saved;
                });
        when(mapper.toDepositResponseDto(any(Transaction.class))).thenReturn(expectedResponse);

        DepositResponseDto response = transactionService.deposit(USER_ID, new BigDecimal("1000.00"),
                null);

        assertThat(response).isSameAs(expectedResponse);

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(transactionCaptor.capture());
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(savedTransaction.getOrderId()).isNull();
        assertThat(savedTransaction.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("6000.00"));

        verify(accountRepository).save(account);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("6000.00"));
    }

    @Test
    @DisplayName("deposit: при невалидной сумме должно бросить BillingOperationException " +
            "с кодом BILLING_INVALID_AMOUNT")
    void shouldThrowInvalidAmountOnDeposit() {
        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.deposit(USER_ID, BigDecimal.ZERO, null));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_INVALID_AMOUNT);
        verify(accountRepository, never()).findByUserId(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("deposit: при заблокированном счёте должно бросить BillingOperationException " +
            "с кодом BILLING_ACCOUNT_INACTIVE")
    void shouldThrowAccountInactiveOnDeposit() {
        Account account = activeAccount(INITIAL_BALANCE);
        account.setLocked(true);
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.deposit(USER_ID, AMOUNT, null));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_INACTIVE);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("deposit: повтор с тем же idempotencyKey - одна транзакция, возвращается прежний результат")
    void shouldReplayDepositWithSameIdempotencyKey() {
        Transaction existingDeposit = transaction(300L, Transaction.TransactionType.DEPOSIT, AMOUNT,
                INITIAL_BALANCE, new BigDecimal("6500.00"));
        existingDeposit.setOrderId(null);
        existingDeposit.setIdempotencyKey(DEPOSIT_KEY);
        DepositResponseDto previousResult = new DepositResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("6500.00"), 300L);
        when(transactionRepository.findByIdempotencyKey(DEPOSIT_KEY)).thenReturn(Optional.of(existingDeposit));
        when(mapper.toDepositResponseDto(existingDeposit)).thenReturn(previousResult);

        DepositResponseDto response = transactionService.deposit(USER_ID, AMOUNT, DEPOSIT_KEY);

        assertThat(response).isSameAs(previousResult);
        verify(transactionRepository, never()).save(any());
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("deposit: повтор ключа с другой суммой - 409 IDEMPOTENCY_KEY_CONFLICT")
    void shouldThrowConflictWhenDepositKeyReusedWithDifferentAmount() {
        Transaction existingDeposit = transaction(300L, Transaction.TransactionType.DEPOSIT, AMOUNT,
                INITIAL_BALANCE, new BigDecimal("6500.00"));
        existingDeposit.setOrderId(null);
        existingDeposit.setIdempotencyKey(DEPOSIT_KEY);
        when(transactionRepository.findByIdempotencyKey(DEPOSIT_KEY)).thenReturn(Optional.of(existingDeposit));

        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.deposit(USER_ID, new BigDecimal("999.00"), DEPOSIT_KEY));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT);
        verify(transactionRepository, never()).save(any());
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit: новый idempotencyKey - транзакция сохраняется с ключом")
    void shouldStoreIdempotencyKeyOnNewDeposit() {
        Account account = activeAccount(INITIAL_BALANCE);
        DepositResponseDto expectedResponse = new DepositResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("6500.00"), 300L);
        when(transactionRepository.findByIdempotencyKey(DEPOSIT_KEY)).thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenAnswer(invocation -> {
                    Transaction saved = invocation.getArgument(0);
                    saved.setId(300L);
                    return saved;
                });
        when(mapper.toDepositResponseDto(any(Transaction.class))).thenReturn(expectedResponse);

        DepositResponseDto response = transactionService.deposit(USER_ID, AMOUNT, DEPOSIT_KEY);
        
        assertThat(response).isSameAs(expectedResponse);

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).saveAndFlush(transactionCaptor.capture());
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(savedTransaction.getIdempotencyKey()).isEqualTo(DEPOSIT_KEY);
        assertThat(savedTransaction.getOrderId()).isNull();
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit: гонка на уникальном индексе - повторный SELECT и replay вместо 500")
    void shouldReplayWhenIdempotencyKeyInsertRaces() {

        Account account = activeAccount(INITIAL_BALANCE);
        Transaction winner = transaction(300L, Transaction.TransactionType.DEPOSIT, AMOUNT,
                INITIAL_BALANCE, new BigDecimal("6500.00"));
        winner.setOrderId(null);
        winner.setIdempotencyKey(DEPOSIT_KEY);
        DepositResponseDto winnerResult = new DepositResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("6500.00"), 300L);
        when(transactionRepository.findByIdempotencyKey(DEPOSIT_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_transactions_idempotency_key\""));
        when(mapper.toDepositResponseDto(winner)).thenReturn(winnerResult);

        DepositResponseDto response = transactionService.deposit(USER_ID, AMOUNT, DEPOSIT_KEY);

        assertThat(response).isSameAs(winnerResult);
    }

    @Test
    @DisplayName("withdraw: replay с той же суммой и владельцем - возвращается прежняя транзакция")
    void shouldReplayWithdrawWhenSamePayload() {
        Transaction existingWithdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        WithdrawResponseDto previousResult = new WithdrawResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("3500.00"), true, WITHDRAWAL_TX_ID);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(existingWithdrawal));
        when(mapper.toWithdrawResponseDto(existingWithdrawal)).thenReturn(previousResult);

        WithdrawResponseDto response = transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID);

        assertThat(response).isSameAs(previousResult);
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw: replay с другой суммой - 409 IDEMPOTENCY_KEY_CONFLICT")
    void shouldThrowConflictWhenWithdrawReplayedWithDifferentAmount() {
        Transaction existingWithdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(existingWithdrawal));

        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, new BigDecimal("777.00"), ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT);
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw: replay с другим владельцем - 409 IDEMPOTENCY_KEY_CONFLICT")
    void shouldThrowConflictWhenWithdrawReplayedWithDifferentUser() {
        Transaction existingWithdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(existingWithdrawal));

        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(999L, AMOUNT, ORDER_ID));

        assertThat(ex.getCode()).isEqualTo(ErrorCodes.IDEMPOTENCY_KEY_CONFLICT);
        verify(transactionRepository, never()).saveAndFlush(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw: гонка на уникальном индексе (order_id, type) - повторный SELECT и replay вместо 500")
    void shouldReplayWithdrawWhenUniqueIndexRaces() {
        Account account = activeAccount(INITIAL_BALANCE);
        Transaction winner = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        WithdrawResponseDto winnerResult = new WithdrawResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("3500.00"), true, WITHDRAWAL_TX_ID);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.saveAndFlush(any(Transaction.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_transactions_order_id_type\""));
        when(mapper.toWithdrawResponseDto(winner)).thenReturn(winnerResult);

        WithdrawResponseDto response = transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID);

        assertThat(response).isSameAs(winnerResult);
    }

    @Test
    @DisplayName("getWithdrawStatus: при наличии списания возвращает withdrawn=true с данными транзакции")
    void shouldReturnWithdrawnTrueWhenWithdrawalExists() {
        Transaction withdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(withdrawal));

        WithdrawStatusDto status = transactionService.getWithdrawStatus(ORDER_ID);

        assertThat(status.withdrawn()).isTrue();
        assertThat(status.orderId()).isEqualTo(ORDER_ID);
        assertThat(status.transactionId()).isEqualTo(WITHDRAWAL_TX_ID);
        assertThat(status.amount()).isEqualByComparingTo(AMOUNT);
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("getWithdrawStatus: при отсутствии списания возвращает withdrawn=false без побочных эффектов")
    void shouldReturnWithdrawnFalseWhenNoWithdrawal() {
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());

        WithdrawStatusDto status = transactionService.getWithdrawStatus(ORDER_ID);

        assertThat(status.withdrawn()).isFalse();
        assertThat(status.orderId()).isEqualTo(ORDER_ID);
        assertThat(status.transactionId()).isNull();
        assertThat(status.amount()).isNull();
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }
}
