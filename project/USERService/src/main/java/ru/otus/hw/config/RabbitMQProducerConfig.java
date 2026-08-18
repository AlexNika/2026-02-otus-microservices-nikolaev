package ru.otus.hw.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.otus.hw.config.properties.RabbitMQProperties;

/**
 * Producer-конфигурация RabbitMQ USERService: exchange для UserCreatedEvent (users.events)
 * и exchange для best-effort уведомлений (notifications.events), JSON-конвертер и RabbitTemplate.
 */
@Configuration
@RequiredArgsConstructor
public class RabbitMQProducerConfig {

    private final RabbitMQProperties rabbitMQProperties;

    @Bean
    public DirectExchange userEventsExchange() {
        return new DirectExchange(rabbitMQProperties.getProducer().getExchangeName(), true, false);
    }

    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(rabbitMQProperties.getNotification().getExchangeName(), true, false);
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter);
        return rabbitTemplate;
    }
}
