package ru.otus.hw.service;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import ru.otus.hw.dto.AccountCreateDto;
import ru.otus.hw.dto.AccountResponseDto;
import ru.otus.hw.dto.mapper.AccountMapper;
import ru.otus.hw.models.Account;
import ru.otus.hw.repository.AccountRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Идемпотентность createAccount по natural key userId, включая гонку параллельных вставок:
 * DataIntegrityViolationException на insert -> повторный SELECT в новой транзакции ->
 * возврат существующего аккаунта вместо 409.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceImplTest {

    private static final Long USER_ID = 7L;

    private static final Long ACCOUNT_ID = 55L;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionService transactionService;

    @Mock
    private AccountMapper mapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AccountServiceImpl accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountServiceImpl(accountRepository, transactionService, mapper,
                transactionTemplate);
    }

    private static @NonNull Account account() {
        Account account = Account.builder()
                .userId(USER_ID)
                .balance(BigDecimal.ZERO)
                .build();
        account.setId(ACCOUNT_ID);
        return account;
    }

    @Test
    @DisplayName("аккаунт уже существует: возвращается существующий, вставка не выполняется")
    void shouldReturnExistingAccount() {
        AccountResponseDto dto = new AccountResponseDto(ACCOUNT_ID, USER_ID, BigDecimal.ZERO, true, false);
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account()));
        when(mapper.toAccountResponseDto(any(Account.class))).thenReturn(dto);

        AccountResponseDto result = accountService.createAccount(new AccountCreateDto(USER_ID));

        assertThat(result).isEqualTo(dto);
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    @DisplayName("новый аккаунт: создаётся короткой транзакцией через TransactionTemplate")
    void shouldCreateNewAccount() {
        AccountResponseDto dto = new AccountResponseDto(ACCOUNT_ID, USER_ID, BigDecimal.ZERO, true, false);
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(mapper.toEntity(any(AccountCreateDto.class))).thenReturn(new Account());
        when(accountRepository.saveAndFlush(any(Account.class))).thenAnswer(invocation -> {
            Account saved = invocation.getArgument(0);
            saved.setId(ACCOUNT_ID);
            return saved;
        });
        when(mapper.toAccountResponseDto(any(Account.class))).thenReturn(dto);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        AccountResponseDto result = accountService.createAccount(new AccountCreateDto(USER_ID));

        assertThat(result).isEqualTo(dto);
        verify(accountRepository).saveAndFlush(any(Account.class));
    }

    @Test
    @DisplayName("гонка параллельных вставок: DataIntegrityViolationException -> re-SELECT -> "
            + "возврат аккаунта-победителя вместо 409")
    void shouldReturnWinnerOnUniqueViolationRace() {
        AccountResponseDto dto = new AccountResponseDto(ACCOUNT_ID, USER_ID, BigDecimal.ZERO, true, false);
        Account winner = account();
        when(accountRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(transactionTemplate.execute(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_accounts_user_id\""));
        when(mapper.toAccountResponseDto(winner)).thenReturn(dto);

        AccountResponseDto result = accountService.createAccount(new AccountCreateDto(USER_ID));

        assertThat(result).isEqualTo(dto);
    }

    @Test
    @DisplayName("гонка без победителя (аномалия): DataIntegrityViolationException пробрасывается дальше")
    void shouldRethrowWhenWinnerNotFound() {
        when(accountRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(transactionTemplate.execute(any()))
                .thenThrow(new DataIntegrityViolationException("constraint violation"));

        assertThrows(DataIntegrityViolationException.class,
                () -> accountService.createAccount(new AccountCreateDto(USER_ID)));
    }

    @Test
    @DisplayName("некорректный userId: IllegalArgumentException без обращения к репозиторию")
    void shouldRejectInvalidUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> accountService.createAccount(new AccountCreateDto(0L)));
        assertThrows(IllegalArgumentException.class,
                () -> accountService.createAccount(new AccountCreateDto(null)));
        verify(accountRepository, never()).findByUserId(any());
    }
}
