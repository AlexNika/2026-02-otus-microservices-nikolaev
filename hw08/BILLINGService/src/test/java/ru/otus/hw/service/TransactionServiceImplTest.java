package ru.otus.hw.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.otus.hw.dto.DepositResponseDto;
import ru.otus.hw.dto.RefundResponseDto;
import ru.otus.hw.dto.WithdrawResponseDto;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceImplTest {

    private static final Long USER_ID = 1L;

    private static final Long ACCOUNT_ID = 10L;

    private static final Long ORDER_ID = 100L;

    private static final Long WITHDRAWAL_TX_ID = 500L;

    private static final Long REFUND_TX_ID = 501L;

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("5000.00");

    private static final BigDecimal AMOUNT = new BigDecimal("1500.00");

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountMapper mapper;

    @InjectMocks
    private TransactionServiceImpl transactionService;

    private static Account activeAccount(BigDecimal balance) {
        Account account = Account.builder()
                .userId(USER_ID)
                .balance(balance)
                .enabled(true)
                .locked(false)
                .build();
        account.setId(ACCOUNT_ID);
        return account;
    }

    private static Transaction transaction(Long id, Transaction.TransactionType type, BigDecimal amount,
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
    @DisplayName("withdraw: должен списать средства, сохранить транзакцию WITHDRAWAL с orderId и вернуть корректный ответ")
    void shouldWithdrawAndSaveWithdrawalTransaction() {
        // given
        Account account = activeAccount(INITIAL_BALANCE);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> {
                    Transaction saved = invocation.getArgument(0);
                    saved.setId(WITHDRAWAL_TX_ID);
                    return saved;
                });

        // when
        WithdrawResponseDto response = transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID);

        // then
        assertThat(response).isEqualTo(new WithdrawResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("3500.00"), true, WITHDRAWAL_TX_ID));

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
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
        // given
        Transaction existingWithdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        WithdrawResponseDto previousResult = new WithdrawResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("3500.00"), true, WITHDRAWAL_TX_ID);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(existingWithdrawal));
        when(mapper.toWithdrawResponseDto(existingWithdrawal)).thenReturn(previousResult);

        // when
        WithdrawResponseDto response = transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID);

        // then
        assertThat(response).isSameAs(previousResult);
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("withdraw: при недостатке средств должен бросить BillingOperationException с кодом BILLING_INSUFFICIENT_FUNDS")
    void shouldThrowInsufficientFundsOnWithdraw() {
        // given
        Account account = activeAccount(new BigDecimal("100.00"));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        // when
        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID));

        // then
        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_INSUFFICIENT_FUNDS);
        assertThat(ex.getUserId()).isEqualTo(USER_ID);
        assertThat(ex.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(ex.getRequiredAmount()).isEqualByComparingTo(AMOUNT);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw: при заблокированном счёте должен бросить BillingOperationException с кодом BILLING_ACCOUNT_INACTIVE")
    void shouldThrowAccountInactiveWhenLockedOnWithdraw() {
        // given
        Account account = activeAccount(INITIAL_BALANCE);
        account.setLocked(true);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        // when
        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID));

        // then
        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_INACTIVE);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw: при отключённом счёте должен бросить BillingOperationException с кодом BILLING_ACCOUNT_INACTIVE")
    void shouldThrowAccountInactiveWhenDisabledOnWithdraw() {
        // given
        Account account = activeAccount(INITIAL_BALANCE);
        account.setEnabled(false);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        // when
        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID));

        // then
        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_INACTIVE);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw: при отсутствии счёта должен бросить BillingOperationException с кодом BILLING_ACCOUNT_NOT_FOUND")
    void shouldThrowAccountNotFoundOnWithdraw() {
        // given
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());

        // when
        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, AMOUNT, ORDER_ID));

        // then
        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_NOT_FOUND);
        assertThat(ex.getUserId()).isEqualTo(USER_ID);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("withdraw/refund: при невалидной сумме должно бросить BillingOperationException с кодом BILLING_INVALID_AMOUNT")
    void shouldThrowInvalidAmountOnWithdrawAndRefund() {
        // when
        BillingOperationException withdrawEx = assertThrows(BillingOperationException.class,
                () -> transactionService.withdraw(USER_ID, BigDecimal.ZERO, ORDER_ID));
        BillingOperationException refundEx = assertThrows(BillingOperationException.class,
                () -> transactionService.refund(USER_ID, AMOUNT.negate(), ORDER_ID));

        // then
        assertThat(withdrawEx.getCode()).isEqualTo(ErrorCodes.BILLING_INVALID_AMOUNT);
        assertThat(refundEx.getCode()).isEqualTo(ErrorCodes.BILLING_INVALID_AMOUNT);
        verify(accountRepository, never()).findByUserId(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("refund: должен вернуть деньги, сохранить транзакцию REFUND и ответить success=true")
    void shouldRefundAndSaveRefundTransaction() {
        // given
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

        // when
        RefundResponseDto response = transactionService.refund(USER_ID, AMOUNT, ORDER_ID);

        // then
        assertThat(response).isEqualTo(new RefundResponseDto(true, REFUND_TX_ID, new BigDecimal("5000.00"), null));

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
    @DisplayName("refund: при отсутствии списания по orderId должен вернуть мягкий no-op без изменения баланса и сохранений")
    void shouldReturnNoOpWhenNoWithdrawalForRefund() {
        // given
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.empty());

        // when
        RefundResponseDto response = transactionService.refund(USER_ID, AMOUNT, ORDER_ID);

        // then
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
        // given
        Transaction withdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        Transaction existingRefund = transaction(REFUND_TX_ID, Transaction.TransactionType.REFUND,
                AMOUNT, new BigDecimal("3500.00"), INITIAL_BALANCE);
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(withdrawal));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.REFUND))
                .thenReturn(Optional.of(existingRefund));

        // when
        RefundResponseDto response = transactionService.refund(USER_ID, AMOUNT, ORDER_ID);

        // then
        assertThat(response).isEqualTo(new RefundResponseDto(true, REFUND_TX_ID, INITIAL_BALANCE, null));
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).findByUserId(any());
    }

    @Test
    @DisplayName("refund: при заблокированном счёте после списания должен бросить BillingOperationException с кодом BILLING_ACCOUNT_INACTIVE")
    void shouldThrowAccountInactiveOnRefund() {
        // given
        Account account = activeAccount(new BigDecimal("3500.00"));
        account.setLocked(true);
        Transaction withdrawal = transaction(WITHDRAWAL_TX_ID, Transaction.TransactionType.WITHDRAWAL,
                AMOUNT, INITIAL_BALANCE, new BigDecimal("3500.00"));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.WITHDRAWAL))
                .thenReturn(Optional.of(withdrawal));
        when(transactionRepository.findByOrderIdAndTransactionType(ORDER_ID, Transaction.TransactionType.REFUND))
                .thenReturn(Optional.empty());
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        // when
        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.refund(USER_ID, AMOUNT, ORDER_ID));

        // then
        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_INACTIVE);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit: при успешном пополнении должен увеличить баланс и сохранить транзакцию DEPOSIT без orderId")
    void shouldDepositSuccessfully() {
        // given
        Account account = activeAccount(INITIAL_BALANCE);
        DepositResponseDto expectedResponse = new DepositResponseDto(USER_ID, ACCOUNT_ID,
                new BigDecimal("6000.00"), 300L);
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> {
                    Transaction saved = invocation.getArgument(0);
                    saved.setId(300L);
                    return saved;
                });
        when(mapper.toDepositResponseDto(any(Transaction.class))).thenReturn(expectedResponse);

        // when
        DepositResponseDto response = transactionService.deposit(USER_ID, new BigDecimal("1000.00"));

        // then
        assertThat(response).isSameAs(expectedResponse);

        ArgumentCaptor<Transaction> transactionCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        Transaction savedTransaction = transactionCaptor.getValue();
        assertThat(savedTransaction.getTransactionType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(savedTransaction.getOrderId()).isNull();
        assertThat(savedTransaction.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("6000.00"));

        verify(accountRepository).save(account);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("6000.00"));
    }

    @Test
    @DisplayName("deposit: при невалидной сумме должно бросить BillingOperationException с кодом BILLING_INVALID_AMOUNT")
    void shouldThrowInvalidAmountOnDeposit() {
        // when
        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.deposit(USER_ID, BigDecimal.ZERO));

        // then
        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_INVALID_AMOUNT);
        verify(accountRepository, never()).findByUserId(any());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("deposit: при заблокированном счёте должно бросить BillingOperationException с кодом BILLING_ACCOUNT_INACTIVE")
    void shouldThrowAccountInactiveOnDeposit() {
        // given
        Account account = activeAccount(INITIAL_BALANCE);
        account.setLocked(true);
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        // when
        BillingOperationException ex = assertThrows(BillingOperationException.class,
                () -> transactionService.deposit(USER_ID, AMOUNT));

        // then
        assertThat(ex.getCode()).isEqualTo(ErrorCodes.BILLING_ACCOUNT_INACTIVE);
        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }
}
