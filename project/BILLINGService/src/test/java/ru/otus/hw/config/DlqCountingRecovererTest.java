package ru.otus.hw.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * DlqCountingRecoverer: метрика до делегирования, тег по received routing key.
 */
class DlqCountingRecovererTest {

    private SimpleMeterRegistry registry;

    private MessageRecoverer delegate;

    private DlqCountingRecoverer recoverer;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        delegate = mock(MessageRecoverer.class);
        recoverer = new DlqCountingRecoverer(delegate, registry);
    }

    private static Message messageWithRoutingKey(String routingKey) {
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(routingKey);
        return new Message("{}".getBytes(), properties);
    }

    @Test
    @DisplayName("сообщение с известным routing_key: метрика с тегом + делегирование")
    void shouldCountMessageToDlqAndDelegate() {
        Message message = messageWithRoutingKey("notification.event");
        RuntimeException poison = new RuntimeException("poison");

        recoverer.recover(message, poison);

        Counter counter = registry.find("consumer.messages.to.dlq")
                .tags("routing_key", "notification.event").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        verify(delegate).recover(message, poison);
    }

    @Test
    @DisplayName("routing_key отсутствует: тег unknown, делегат всё равно вызывается")
    void shouldUseUnknownTagWhenRoutingKeyMissing() {
        Message message = messageWithRoutingKey(null);

        recoverer.recover(message, new RuntimeException("poison"));

        Counter counter = registry.find("consumer.messages.to.dlq").tags("routing_key", "unknown").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        verify(delegate).recover(any(Message.class), any(Throwable.class));
    }

    @Test
    @DisplayName("дефолтный делегат: после подсчёта сообщение отклоняется без requeue")
    void shouldDelegateToRejectAndDontRequeueByDefault() {
        DlqCountingRecoverer defaultRecoverer = new DlqCountingRecoverer(registry);

        assertThatThrownBy(() -> defaultRecoverer.recover(messageWithRoutingKey("notification.event"),
                new RuntimeException("poison")))
                .isInstanceOf(org.springframework.amqp.rabbit.support.ListenerExecutionFailedException.class)
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    @DisplayName("известные очереди предрегистрируются со значением 0 (видимость первого инкремента для increase())")
    void shouldPreRegisterKnownRoutingKeys() {
        new DlqCountingRecoverer(registry, "user.created");

        assertThat(Objects.requireNonNull(registry
                        .find("consumer.messages.to.dlq")
                        .tags("routing_key", "user.created")
                        .counter())
                .count())
                .isZero();
        assertThat(Objects.requireNonNull(registry
                        .find("consumer.messages.to.dlq")
                        .tags("routing_key", "unknown")
                        .counter())
                .count())
                .isZero();
    }
}
