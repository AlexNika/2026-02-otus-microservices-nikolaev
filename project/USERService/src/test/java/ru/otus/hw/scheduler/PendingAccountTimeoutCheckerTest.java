package ru.otus.hw.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.config.properties.ActivationProperties;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.models.AccountStatus;
import ru.otus.hw.models.User;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тайм-аут проверка активации биллинг-аккаунтов: 200 -> ACTIVE, 404 -> BLOCKED,
 * сбой клиента -> fail-open без смены статуса и уведомлений.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendingAccountTimeoutCheckerTest {

    private static final Long USER_ID = 7L;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BillingServiceClient billingServiceClient;

    @Mock
    private NotificationEventPublisher notificationEventPublisher;

    private PendingAccountTimeoutChecker checker;

    @BeforeEach
    void setUp() {
        ActivationProperties activationProperties = new ActivationProperties();
        activationProperties.setTimeout(Duration.ofMinutes(30));
        activationProperties.setCheckIntervalMs(300000L);
        checker = new PendingAccountTimeoutChecker(userRepository, billingServiceClient,
                notificationEventPublisher, activationProperties);
    }

    private static User pendingUser(LocalDateTime created) {
        User user = User.builder()
                .email("john@example.com")
                .accountStatus(AccountStatus.PENDING)
                .build();
        user.setId(USER_ID);
        user.setCreated(created);
        return user;
    }

    @Test
    @DisplayName("проверяются только PENDING-пользователи старше тайм-аута (cutoff = now - 30m)")
    void shouldQueryPendingUsersOlderThanTimeout() {
        when(userRepository.findByAccountStatusAndCreatedBefore(any(), any())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now().minusMinutes(31);

        checker.checkPendingUsers();

        ArgumentCaptor<AccountStatus> statusCaptor = ArgumentCaptor.forClass(AccountStatus.class);
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).findByAccountStatusAndCreatedBefore(statusCaptor.capture(), cutoffCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(AccountStatus.PENDING);
        assertThat(cutoffCaptor.getValue()).isAfter(before);
        assertThat(cutoffCaptor.getValue()).isBefore(LocalDateTime.now());
        verify(billingServiceClient, never()).accountExists(any());
    }

    @Test
    @DisplayName("аккаунт появился (200): ACTIVE + уведомление ACCOUNT_ACTIVATED")
    void shouldActivateUserWhenAccountExists() {
        User user = pendingUser(LocalDateTime.now().minusMinutes(31));
        when(userRepository.findByAccountStatusAndCreatedBefore(any(), any())).thenReturn(List.of(user));
        when(billingServiceClient.accountExists(USER_ID)).thenReturn(true);

        checker.checkPendingUsers();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);

        verify(notificationEventPublisher).publish(eq(USER_ID), eq("ACCOUNT_ACTIVATED"), anyString());
    }

    @Test
    @DisplayName("аккаунта нет (404): BLOCKED + уведомление ACCOUNT_CREATION_FAILED про тайм-аут")
    void shouldBlockUserWhenAccountMissing() {
        User user = pendingUser(LocalDateTime.now().minusMinutes(31));
        when(userRepository.findByAccountStatusAndCreatedBefore(any(), any())).thenReturn(List.of(user));
        when(billingServiceClient.accountExists(USER_ID)).thenReturn(false);

        checker.checkPendingUsers();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountStatus()).isEqualTo(AccountStatus.BLOCKED);

        verify(notificationEventPublisher).publish(eq(USER_ID), eq("ACCOUNT_CREATION_FAILED"),
                contains("тайм-аут"));
    }

    @Test
    @DisplayName("сбой клиента: fail-open - статус не меняется, уведомление не публикуется, "
            + "повтор на следующем тике")
    void shouldSkipUserOnClientError() {
        User user = pendingUser(LocalDateTime.now().minusMinutes(31));
        when(userRepository.findByAccountStatusAndCreatedBefore(any(), any())).thenReturn(List.of(user));
        when(billingServiceClient.accountExists(USER_ID))
                .thenThrow(new BillingServiceException("billing unavailable"));

        checker.checkPendingUsers();

        verify(userRepository, never()).save(any(User.class));
        verify(notificationEventPublisher, never()).publish(any(), anyString(), anyString());
    }
}
