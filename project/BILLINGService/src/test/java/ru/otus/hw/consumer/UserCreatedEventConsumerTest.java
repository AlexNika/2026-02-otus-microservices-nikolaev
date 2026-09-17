package ru.otus.hw.consumer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import ru.otus.hw.config.properties.RabbitMQProperties;
import ru.otus.hw.dto.AccountCreateDto;
import ru.otus.hw.dto.AccountCreatedEvent;
import ru.otus.hw.dto.AccountResponseDto;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.producer.AccountEventPublisher;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.service.AccountService;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Приём UserCreatedEvent в BILLINGService: успех (AccountCreatedEvent + ACCOUNT_CREATED
 * с eventId входного события), сбой (детерминированный eventId ACCOUNT_CREATION_FAILED,
 * проброс исключения для DLQ-пути), best-effort уведомления не ломают основной поток.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserCreatedEventConsumerTest {

    private static final Long USER_ID = 7L;

    private static final Long ACCOUNT_ID = 55L;

    private static final String INCOMING_EVENT_ID = "evt-user-created-1";

    private static final String EXPECTED_FAILURE_EVENT_ID = UUID
            .nameUUIDFromBytes(("account-create-failed:" + USER_ID).getBytes(StandardCharsets.UTF_8))
            .toString();

    @Mock
    private AccountService accountService;

    @Mock
    private AccountEventPublisher accountEventPublisher;

    @Mock
    private NotificationEventPublisher notificationEventPublisher;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Mock
    private W3CTraceContextAdapter traceContextAdapter;

    private UserCreatedEventConsumer consumer() {
        return new UserCreatedEventConsumer(accountService, accountEventPublisher, notificationEventPublisher,
                traceContextAdapter, meterRegistry);
    }

    private static @NonNull UserCreatedEvent event() {
        return UserCreatedEvent.builder()
                .eventId(INCOMING_EVENT_ID)
                .userId(USER_ID)
                .email("john@example.com")
                .timestamp(Instant.now())
                .build();
    }

    @Test
    @DisplayName("успех: аккаунт создан, AccountCreatedEvent с accountId, уведомление ACCOUNT_CREATED "
            + "с eventId входного события")
    void shouldCreateAccountAndPublishEvents() {
        when(accountService.createAccount(any(AccountCreateDto.class)))
                .thenReturn(new AccountResponseDto(ACCOUNT_ID, USER_ID, BigDecimal.ZERO, true, false));

        consumer().handleUserCreated(event(), Map.of());

        ArgumentCaptor<AccountCreateDto> createCaptor = ArgumentCaptor.forClass(AccountCreateDto.class);
        verify(accountService).createAccount(createCaptor.capture());
        assertThat(createCaptor.getValue().userId()).isEqualTo(USER_ID);

        ArgumentCaptor<AccountCreatedEvent> accountEventCaptor =
                ArgumentCaptor.forClass(AccountCreatedEvent.class);
        verify(accountEventPublisher).publish(accountEventCaptor.capture());
        assertThat(accountEventCaptor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(accountEventCaptor.getValue().accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(accountEventCaptor.getValue().eventId()).isNotBlank();

        verify(notificationEventPublisher).publish(eq(INCOMING_EVENT_ID), eq(USER_ID), eq("ACCOUNT_CREATED"),
                contains(String.valueOf(ACCOUNT_ID)));
        assertThat(meterRegistry.counter("billing.accounts.opened").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("billing.accounts.creation.failures").count()).isZero();
    }

    @Test
    @DisplayName("повторная доставка того же события: уведомление ACCOUNT_CREATED несёт тот же eventId - "
            + "дедупликация в NOTIFICATION")
    void shouldReuseIncomingEventIdOnRedelivery() {
        when(accountService.createAccount(any(AccountCreateDto.class)))
                .thenReturn(new AccountResponseDto(ACCOUNT_ID, USER_ID, BigDecimal.ZERO, true, false));

        consumer().handleUserCreated(event(), Map.of());
        consumer().handleUserCreated(event(), Map.of());

        ArgumentCaptor<String> eventIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationEventPublisher, org.mockito.Mockito.times(2)).publish(eventIdCaptor.capture(),
                eq(USER_ID), eq("ACCOUNT_CREATED"), anyString());
        assertThat(eventIdCaptor.getAllValues()).containsExactly(INCOMING_EVENT_ID, INCOMING_EVENT_ID);
    }

    @Test
    @DisplayName("сбой createAccount: уведомление ACCOUNT_CREATION_FAILED с детерминированным eventId, "
            + "исключение пробрасывается для listener-retry и DLQ")
    void shouldNotifyFailureAndRethrow() {
        when(accountService.createAccount(any(AccountCreateDto.class)))
                .thenThrow(new IllegalStateException("billing postgres is down"));

        UserCreatedEventConsumer consumer = consumer();
        assertThatThrownBy(() -> consumer.handleUserCreated(event(), Map.of()))
                .isInstanceOf(IllegalStateException.class);

        verify(notificationEventPublisher).publish(eq(EXPECTED_FAILURE_EVENT_ID), eq(USER_ID),
                eq("ACCOUNT_CREATION_FAILED"), contains("billing postgres is down"));
        assertThat(meterRegistry.counter("billing.accounts.creation.failures").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("billing.accounts.opened").count()).isZero();
    }

    @Test
    @DisplayName("детерминированный eventId сбоя одинаков для повторных доставок одного userId")
    void shouldKeepDeterministicFailureEventIdAcrossRetries() {
        when(accountService.createAccount(any(AccountCreateDto.class)))
                .thenThrow(new IllegalStateException("first failure"))
                .thenThrow(new IllegalStateException("second failure"));

        UserCreatedEventConsumer consumer = consumer();
            assertThatThrownBy(() -> consumer.handleUserCreated(event(),
                    Map.of())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> consumer.handleUserCreated(event(),
                    Map.of())).isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> eventIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationEventPublisher, org.mockito.Mockito.times(2)).publish(eventIdCaptor.capture(),
                eq(USER_ID), eq("ACCOUNT_CREATION_FAILED"), anyString());
        assertThat(eventIdCaptor.getAllValues())
                .containsExactly(EXPECTED_FAILURE_EVENT_ID, EXPECTED_FAILURE_EVENT_ID);
    }

    @Test
    @DisplayName("сбой публикации уведомления не ломает основной поток: событие обработано полностью")
    void shouldNotBreakFlowWhenNotificationPublishFails() {
        when(accountService.createAccount(any(AccountCreateDto.class)))
                .thenReturn(new AccountResponseDto(ACCOUNT_ID, USER_ID, BigDecimal.ZERO, true, false));
        NotificationEventPublisher failingPublisher = realPublisherWithFailingBroker();
        UserCreatedEventConsumer consumer =
                new UserCreatedEventConsumer(accountService, accountEventPublisher, failingPublisher,
                        mock(W3CTraceContextAdapter.class), meterRegistry);

        assertThatCode(() -> consumer.handleUserCreated(event(), Map.of())).doesNotThrowAnyException();

        verify(accountEventPublisher).publish(any(AccountCreatedEvent.class));
    }

    /**
     * Реальный NotificationEventPublisher с падающим брокером: проверяется, что best-effort
     * публикация глотает ошибку и не пробрасывает её consumer'у.
     */
    private static NotificationEventPublisher realPublisherWithFailingBroker() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
        return new NotificationEventPublisher(rabbitTemplate, new RabbitMQProperties(),
                mock(W3CTraceContextAdapter.class));
    }
}
