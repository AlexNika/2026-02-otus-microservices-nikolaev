package ru.otus.hw.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.otus.hw.config.properties.RabbitMQProperties;

@Slf4j
@EnableRabbit
@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final RabbitMQProperties rabbitMQProperties;

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(rabbitMQProperties.getExchangeName(), true, false);
    }

    /**
     * Основная очередь уведомлений. Настроена "мёртвая петля": сообщения, которые consumer
     * не смог обработать даже после ретраев (см. {@link RabbitMQListenerConfig}), отклоняются
     * без requeue и брокер перенаправляет их в {@code notification.queue.dlq} через DLX
     * (x-dead-letter-exchange на тот же exchange + x-dead-letter-routing-key), а не выбрасывает.
     *
     * <p>Эксплуатационная пометка: RabbitMQ отклоняет изменение аргументов уже существующей
     * очереди, поэтому на занятом брокере перед деплоем очередь нужно удалить
     * (management UI / rabbitmqctl), иначе она не пересоздастся с DLX-аргументами.
     */
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(rabbitMQProperties.getQueueName())
                .withArgument("x-queue-type", rabbitMQProperties.getQueueType())
                .withArgument("x-dead-letter-exchange", rabbitMQProperties.getExchangeName())
                .withArgument("x-dead-letter-routing-key", rabbitMQProperties.getRoutingKey() + ".dlq")
                .build();
    }

    @Bean
    public Queue notificationDlq() {
        return QueueBuilder.durable(rabbitMQProperties.getQueueName() + ".dlq")
                .withArgument("x-queue-type", rabbitMQProperties.getQueueType())
                .build();
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue())
                .to(notificationExchange())
                .with(rabbitMQProperties.getRoutingKey());
    }

    @Bean
    public Binding notificationDlqBinding() {
        return BindingBuilder.bind(notificationDlq())
                .to(notificationExchange())
                .with(rabbitMQProperties.getRoutingKey() + ".dlq");
    }
}
