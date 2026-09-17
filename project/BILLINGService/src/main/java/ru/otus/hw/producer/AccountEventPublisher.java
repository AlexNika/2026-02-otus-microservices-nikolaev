package ru.otus.hw.producer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import ru.otus.hw.config.properties.RabbitMQProperties;
import ru.otus.hw.dto.AccountCreatedEvent;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

/**
 * Публикация AccountCreatedEvent в канал accounts.events / account.created
 * (BILLING → USER). Прямая публикация без outbox: USER идемпотентен по userId,
 * недоставка компенсируется его тайм-аут проверкой активации.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    private final RabbitMQProperties rabbitMQProperties;

    private final W3CTraceContextAdapter traceContextAdapter;

    public void publish(@NonNull AccountCreatedEvent event) {
        rabbitTemplate.convertAndSend(
                rabbitMQProperties.getProducer().getExchangeName(),
                rabbitMQProperties.getProducer().getRoutingKey(),
                event,
                message -> {
                    traceContextAdapter.injectCurrent(message,
                            (carrier, key, value) -> carrier.getMessageProperties().setHeader(key, value));
                    return message;
                });
        log.info("AccountCreatedEvent published: userId={}, accountId={}, eventId={}",
                event.userId(), event.accountId(), event.eventId());
    }
}
