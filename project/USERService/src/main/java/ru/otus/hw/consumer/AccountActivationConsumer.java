package ru.otus.hw.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.AccountCreatedEvent;
import ru.otus.hw.models.AccountStatus;
import ru.otus.hw.models.User;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.UserRepository;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.util.Map;
import java.util.Optional;

/**
 * Приём AccountCreatedEvent (BILLING → USER): биллинг-аккаунт готов - пользователь
 * переводится PENDING/BLOCKED → ACTIVE (BLOCKED→ACTIVE - самовосстановление при
 * позднем появлении аккаунта).
 *
 * <p>Идемпотентность по natural key userId: повторные доставки события для уже
 * ACTIVE-пользователя безопасны (только лог); NOTIFICATION дедуплицирует уведомление
 * по eventId (повторная доставка несёт тот же eventId).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountActivationConsumer {

    private final UserRepository userRepository;

    private final NotificationEventPublisher notificationEventPublisher;

    private final W3CTraceContextAdapter traceContextAdapter;

    @RabbitListener(queues = "${app.rabbitmq.consumer.queue-name:user.account-activation.queue}")
    @Transactional
    public void handleAccountCreated(@NonNull AccountCreatedEvent event, @Headers Map<String, Object> amqpHeaders) {
        try (W3CTraceContextAdapter.Scope ignored =
                traceContextAdapter.open(amqpHeaders, "user.account-created.consume")) {
            log.info("Received AccountCreatedEvent: userId={}, accountId={}, eventId={}",
                    event.userId(), event.accountId(), event.eventId());

            Optional<User> userOptional = userRepository.findById(event.userId());
            if (userOptional.isEmpty()) {
                log.warn("AccountCreatedEvent for unknown userId={}, ack without processing", event.userId());
                return;
            }

            User user = userOptional.get();
            if (user.getAccountStatus() == AccountStatus.ACTIVE) {
                log.info("User id={} is already ACTIVE, AccountCreatedEvent is a no-op", user.getId());
                return;
            }

            AccountStatus previousStatus = user.getAccountStatus();
            user.setAccountStatus(AccountStatus.ACTIVE);
            userRepository.save(user);
            log.info("User id={} activated (was {}), accountId={}",
                    user.getId(), previousStatus, event.accountId());

            notificationEventPublisher.publish(user.getId(), "ACCOUNT_ACTIVATED",
                    "Ваш аккаунт полностью активирован");
        }
    }
}
