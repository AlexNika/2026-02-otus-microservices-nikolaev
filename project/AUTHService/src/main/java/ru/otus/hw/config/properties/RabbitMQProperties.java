package ru.otus.hw.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Топология RabbitMQ AuthService (app.rabbitmq.*):<br>
 * - producer - публикация расширенного UserCreatedEvent (users.events / user.created).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rabbitmq")
public class RabbitMQProperties {

    private ProducerProperties producer = new ProducerProperties();

    @Getter
    @Setter
    public static class ProducerProperties {
        private String exchangeName = "users.events";

        private String routingKey = "user.created";
    }
}
