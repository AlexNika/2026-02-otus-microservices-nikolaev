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

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(rabbitMQProperties.getQueueName())
                .withArgument("x-queue-type", rabbitMQProperties.getQueueType())
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
