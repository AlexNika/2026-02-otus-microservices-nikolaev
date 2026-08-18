package ru.otus.hw.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import ru.otus.hw.config.properties.RabbitMQProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка топологии RabbitMQ NOTIFICATIONService:<br>
 * DLX-аргументы основной очереди, DLQ и bindings.
 */
class RabbitMQConfigTest {

    private RabbitMQConfig rabbitMQConfig;

    @BeforeEach
    void setUp() {
        RabbitMQProperties properties = new RabbitMQProperties();
        properties.setExchangeName("notifications.events");
        properties.setQueueName("notification.queue");
        properties.setQueueType("classic");
        properties.setRoutingKey("notification.event");
        properties.setConsumerEnabled("true");
        rabbitMQConfig = new RabbitMQConfig(properties);
    }

    @Test
    @DisplayName("основная очередь объявлена с DLX-аргументами: ядовитые сообщения уходят в DLQ")
    void shouldDeclareMainQueueWithDeadLetterArguments() {
        Queue queue = rabbitMQConfig.notificationQueue();

        assertThat(queue.getName()).isEqualTo("notification.queue");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-queue-type", "classic")
                .containsEntry("x-dead-letter-exchange", "notifications.events")
                .containsEntry("x-dead-letter-routing-key", "notification.event.dlq");
    }

    @Test
    @DisplayName("DLQ объявлена как durable-очередь notification.queue.dlq")
    void shouldDeclareDurableDlq() {
        Queue dlq = rabbitMQConfig.notificationDlq();

        assertThat(dlq.getName()).isEqualTo("notification.queue.dlq");
        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlq.getArguments()).doesNotContainKey("x-dead-letter-exchange");
    }

    @Test
    @DisplayName("bindings: основная очередь по notification.event, DLQ по notification.event.dlq")
    void shouldBindQueuesWithExpectedRoutingKeys() {
        Binding mainBinding = rabbitMQConfig.notificationBinding();
        Binding dlqBinding = rabbitMQConfig.notificationDlqBinding();

        assertThat(mainBinding.getExchange()).isEqualTo("notifications.events");
        assertThat(mainBinding.getRoutingKey()).isEqualTo("notification.event");
        assertThat(mainBinding.getDestination()).isEqualTo("notification.queue");

        assertThat(dlqBinding.getExchange()).isEqualTo("notifications.events");
        assertThat(dlqBinding.getRoutingKey()).isEqualTo("notification.event.dlq");
        assertThat(dlqBinding.getDestination()).isEqualTo("notification.queue.dlq");
    }
}
