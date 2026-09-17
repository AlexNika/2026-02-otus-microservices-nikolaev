package ru.otus.hw.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.otus.hw.config.properties.RabbitMQProperties;

/**
 * Consumer-конфигурация RabbitMQ BILLINGService: приём UserCreatedEvent из exchange
 * users.events очередью billing.account-create.queue с DLQ-маршрутизацией.
 *
 * <p>Сообщения, которые consumer не смог обработать даже после ретраев
 * (см. {@link RabbitMQListenerConfig}), отклоняются без requeue и брокер перенаправляет их
 * в {@code billing.account-create.queue.dlq} через DLX-аргументы, а не выбрасывает.
 */
@EnableRabbit
@Configuration
@RequiredArgsConstructor
public class RabbitMQConsumerConfig {

    private final RabbitMQProperties rabbitMQProperties;

    @Bean
    public DirectExchange userEventsExchange() {
        return new DirectExchange(rabbitMQProperties.getConsumer().getExchangeName(), true, false);
    }

    @Bean
    public Queue accountCreateQueue() {
        return QueueBuilder.durable(rabbitMQProperties.getConsumer().getQueueName())
                .withArgument("x-queue-type", rabbitMQProperties.getConsumer().getQueueType())
                .withArgument("x-dead-letter-exchange", rabbitMQProperties.getConsumer().getExchangeName())
                .withArgument("x-dead-letter-routing-key",
                        rabbitMQProperties.getConsumer().getRoutingKey() + ".dlq")
                .build();
    }

    @Bean
    public Queue accountCreateDlq() {
        return QueueBuilder.durable(rabbitMQProperties.getConsumer().getQueueName() + ".dlq")
                .withArgument("x-queue-type", rabbitMQProperties.getConsumer().getQueueType())
                .build();
    }

    @Bean
    public Binding accountCreateBinding() {
        return BindingBuilder.bind(accountCreateQueue())
                .to(userEventsExchange())
                .with(rabbitMQProperties.getConsumer().getRoutingKey());
    }

    @Bean
    public Binding accountCreateDlqBinding() {
        return BindingBuilder.bind(accountCreateDlq())
                .to(userEventsExchange())
                .with(rabbitMQProperties.getConsumer().getRoutingKey() + ".dlq");
    }
}
