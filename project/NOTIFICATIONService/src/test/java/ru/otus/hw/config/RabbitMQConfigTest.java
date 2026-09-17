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
 * DLX-аргументы основной очереди, DLQ и bindings (каналы notification.event
 * и user.profile.sync).
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
        RabbitMQProperties.UserSyncProperties userSync = new RabbitMQProperties.UserSyncProperties();
        userSync.setExchangeName("user.sync.events");
        userSync.setQueueName("notification.profile-sync.queue");
        userSync.setQueueType("classic");
        userSync.setRoutingKey("user.profile.sync");
        properties.setUserSync(userSync);
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

    @Test
    @DisplayName("user-sync очередь объявлена с DLX-аргументами user.sync.events/user.profile.sync.dlq")
    void shouldDeclareProfileSyncQueueWithDeadLetterArguments() {
        Queue queue = rabbitMQConfig.notificationProfileSyncQueue();

        assertThat(queue.getName()).isEqualTo("notification.profile-sync.queue");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-queue-type", "classic")
                .containsEntry("x-dead-letter-exchange", "user.sync.events")
                .containsEntry("x-dead-letter-routing-key", "user.profile.sync.dlq");
    }

    @Test
    @DisplayName("user-sync DLQ объявлена как durable-очередь notification.profile-sync.queue.dlq")
    void shouldDeclareProfileSyncDurableDlq() {
        Queue dlq = rabbitMQConfig.notificationProfileSyncDlq();

        assertThat(dlq.getName()).isEqualTo("notification.profile-sync.queue.dlq");
        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlq.getArguments()).doesNotContainKey("x-dead-letter-exchange");
    }

    @Test
    @DisplayName("user-sync bindings: очередь по user.profile.sync, DLQ по user.profile.sync.dlq")
    void shouldBindProfileSyncQueuesWithExpectedRoutingKeys() {
        Binding mainBinding = rabbitMQConfig.notificationProfileSyncBinding();
        Binding dlqBinding = rabbitMQConfig.notificationProfileSyncDlqBinding();

        assertThat(mainBinding.getExchange()).isEqualTo("user.sync.events");
        assertThat(mainBinding.getRoutingKey()).isEqualTo("user.profile.sync");
        assertThat(mainBinding.getDestination()).isEqualTo("notification.profile-sync.queue");

        assertThat(dlqBinding.getExchange()).isEqualTo("user.sync.events");
        assertThat(dlqBinding.getRoutingKey()).isEqualTo("user.profile.sync.dlq");
        assertThat(dlqBinding.getDestination()).isEqualTo("notification.profile-sync.queue.dlq");
    }
}
