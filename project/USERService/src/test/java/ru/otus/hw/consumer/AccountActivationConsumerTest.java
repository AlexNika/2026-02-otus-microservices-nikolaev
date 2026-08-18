package ru.otus.hw.consumer;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.otus.hw.dto.AccountCreatedEvent;
import ru.otus.hw.models.AccountStatus;
import ru.otus.hw.models.User;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.UserRepository;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Приём AccountCreatedEvent: активация пользователя PENDING/BLOCKED -> ACTIVE,
 * идемпотентность повторных доставок, неизвестный userId.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountActivationConsumerTest {

    private static final Long USER_ID = 7L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationEventPublisher notificationEventPublisher;

    @InjectMocks
    private AccountActivationConsumer consumer;

    private static @NonNull User user(AccountStatus status, boolean locked) {
        User user = User.builder()
                .email("john@example.com")
                .accountStatus(status)
                .locked(locked)
                .build();
        user.setId(USER_ID);
        return user;
    }

    private static @NonNull AccountCreatedEvent event() {
        return new AccountCreatedEvent("evt-account-1", USER_ID, 55L, Instant.now());
    }

    @Test
    @DisplayName("PENDING-пользователь: перевод в ACTIVE со снятием locked и уведомлением ACCOUNT_ACTIVATED")
    void shouldActivatePendingUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(AccountStatus.PENDING, false)));

        consumer.handleAccountCreated(event());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(captor.getValue().isLocked()).isFalse();

        verify(notificationEventPublisher).publish(eq(USER_ID), eq("ACCOUNT_ACTIVATED"), anyString());
    }

    @Test
    @DisplayName("BLOCKED-пользователь: самовосстановление BLOCKED -> ACTIVE при позднем появлении аккаунта")
    void shouldActivateBlockedUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(AccountStatus.BLOCKED, true)));

        consumer.handleAccountCreated(event());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(captor.getValue().isLocked()).isFalse();

        verify(notificationEventPublisher).publish(eq(USER_ID), eq("ACCOUNT_ACTIVATED"), anyString());
    }

    @Test
    @DisplayName("повтор для уже ACTIVE-пользователя: no-op без сохранения и уведомлений")
    void shouldDoNothingForAlreadyActiveUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(AccountStatus.ACTIVE, false)));

        consumer.handleAccountCreated(event());

        verify(userRepository, never()).save(any(User.class));
        verify(notificationEventPublisher, never()).publish(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("неизвестный userId: ack без исключения, без сохранения")
    void shouldAckUnknownUserWithoutException() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> consumer.handleAccountCreated(event())).doesNotThrowAnyException();

        verify(userRepository, never()).save(any(User.class));
        verify(notificationEventPublisher, never()).publish(any(), anyString(), anyString());
    }
}
