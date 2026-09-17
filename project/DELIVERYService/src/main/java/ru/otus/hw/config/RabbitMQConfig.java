package ru.otus.hw.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.otus.hw.config.properties.RabbitMQProperties;

/**
 * Конфигурация RabbitMQ DELIVERYService: канал USER → DELIVERY (user.sync.events,
 * topic exchange) для асинхронной репликации адресов доставки пользователя.
 */
@Slf4j
@EnableRabbit
@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final RabbitMQProperties rabbitMQProperties;

    @Bean
    public TopicExchange userSyncExchange() {
        return new TopicExchange(rabbitMQProperties.getUserSync().getExchangeName(), true, false);
    }

    /**
     * Очередь репликации адресов пользователя. Ядовитые сообщения после исчерпания ретраев
     * consumer'а отклоняются без requeue и через DLX (x-dead-letter-exchange на тот же exchange
     * + x-dead-letter-routing-key) уходят в {@code delivery.profile-sync.queue.dlq}.
     */
    @Bean
    public Queue deliveryProfileSyncQueue() {
        RabbitMQProperties.UserSyncProperties userSync = rabbitMQProperties.getUserSync();
        return QueueBuilder.durable(userSync.getQueueName())
                .withArgument("x-queue-type", userSync.getQueueType())
                .withArgument("x-dead-letter-exchange", userSync.getExchangeName())
                .withArgument("x-dead-letter-routing-key", userSync.getRoutingKey() + ".dlq")
                .build();
    }

    @Bean
    public Queue deliveryProfileSyncDlq() {
        RabbitMQProperties.UserSyncProperties userSync = rabbitMQProperties.getUserSync();
        return QueueBuilder.durable(userSync.getQueueName() + ".dlq")
                .withArgument("x-queue-type", userSync.getQueueType())
                .build();
    }

    @Bean
    public Binding deliveryProfileSyncBinding() {
        return BindingBuilder.bind(deliveryProfileSyncQueue())
                .to(userSyncExchange())
                .with(rabbitMQProperties.getUserSync().getRoutingKey());
    }

    @Bean
    public Binding deliveryProfileSyncDlqBinding() {
        return BindingBuilder.bind(deliveryProfileSyncDlq())
                .to(userSyncExchange())
                .with(rabbitMQProperties.getUserSync().getRoutingKey() + ".dlq");
    }
}
