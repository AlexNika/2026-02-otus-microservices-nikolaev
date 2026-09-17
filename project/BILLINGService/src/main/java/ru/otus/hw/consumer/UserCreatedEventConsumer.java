package ru.otus.hw.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;
import ru.otus.hw.dto.AccountCreateDto;
import ru.otus.hw.dto.AccountCreatedEvent;
import ru.otus.hw.dto.AccountResponseDto;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.producer.AccountEventPublisher;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.service.AccountService;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Приём UserCreatedEvent (USER → BILLING): создание биллинг-аккаунта для нового
 * пользователя. Идемпотентен по natural key userId (unique accounts.user_id): повторная
 * доставка события возвращает существующий аккаунт и повторно шлёт AccountCreatedEvent -
 * USER идемпотентен по userId, NOTIFICATION дедуплицирует уведомления по eventId.
 *
 * <p>Успех: AccountCreatedEvent + уведомление ACCOUNT_CREATED (eventId входного события).
 * Сбой: уведомление ACCOUNT_CREATION_FAILED с детерминированным от userId eventId
 * (1..3 listener-ретрая дают одно уведомление), затем проброс исключения - обработают
 * listener-retry и DLQ.
 */
@Slf4j
@Component
public class UserCreatedEventConsumer {

    /**
     * Имя без суффикса {@code .created}: новый prometheus-клиент (в составе
     * micrometer-registry-prometheus) вырезает зарезервированный суффикс
     * {@code _created} из имён метрик, и серия деградировала бы до
     * {@code billing_accounts_total}.
     */
    private static final String METRIC_ACCOUNTS_CREATED = "billing.accounts.opened";

    private static final String METRIC_ACCOUNTS_CREATION_FAILURES = "billing.accounts.creation.failures";

    private final AccountService accountService;

    private final AccountEventPublisher accountEventPublisher;

    private final NotificationEventPublisher notificationEventPublisher;

    private final W3CTraceContextAdapter traceContextAdapter;

    private final Counter accountsCreatedCounter;

    private final Counter accountsCreationFailuresCounter;

    /**
     * Каунтеры предрегистрируются со значением 0: лениво созданный каунтер
     * (первый наблюдаемый сэмпл сразу 1) невидим для increase()/rate().
     */
    public UserCreatedEventConsumer(AccountService accountService, AccountEventPublisher accountEventPublisher,
                                    NotificationEventPublisher notificationEventPublisher,
                                    W3CTraceContextAdapter traceContextAdapter, MeterRegistry meterRegistry) {
        this.accountService = accountService;
        this.accountEventPublisher = accountEventPublisher;
        this.notificationEventPublisher = notificationEventPublisher;
        this.traceContextAdapter = traceContextAdapter;
        this.accountsCreatedCounter = Counter.builder(METRIC_ACCOUNTS_CREATED)
                .description("Billing accounts successfully created")
                .register(meterRegistry);
        this.accountsCreationFailuresCounter = Counter.builder(METRIC_ACCOUNTS_CREATION_FAILURES)
                .description("Billing account creation failures")
                .register(meterRegistry);
    }

    @RabbitListener(queues = "${app.rabbitmq.consumer.queue-name:billing.account-create.queue}")
    public void handleUserCreated(@NonNull UserCreatedEvent event, @Headers Map<String, Object> amqpHeaders) {
        try (W3CTraceContextAdapter.Scope ignored =
                traceContextAdapter.open(amqpHeaders, "billing.user-created.consume")) {
            log.info("Received UserCreatedEvent: userId={}, eventId={}", event.userId(), event.eventId());
            try {
                AccountResponseDto account = accountService.createAccount(new AccountCreateDto(event.userId()));
                accountsCreatedCounter.increment();

                accountEventPublisher.publish(new AccountCreatedEvent(
                        UUID.randomUUID().toString(), event.userId(), account.id(), Instant.now()));

                notificationEventPublisher.publish(event.eventId(), event.userId(), "ACCOUNT_CREATED",
                        "Биллинг-аккаунт готов: id=" + account.id());

            } catch (RuntimeException e) {
                accountsCreationFailuresCounter.increment();
                String failedEventId = deterministicFailureEventId(event.userId());
                notificationEventPublisher.publish(failedEventId, event.userId(), "ACCOUNT_CREATION_FAILED",
                        "Биллинг-аккаунт не создан: " + e.getMessage());
                throw e;
            }
        }
    }

    /**
     * Детерминированный eventId уведомления о сбое: ретраи listener'а (одна и та же
     * доставка) не плодят дубли в NOTIFICATION (дедупликация по eventId).
     */
    private String deterministicFailureEventId(Long userId) {
        return UUID.nameUUIDFromBytes(("account-create-failed:" + userId).getBytes(StandardCharsets.UTF_8))
                .toString();
    }
}
