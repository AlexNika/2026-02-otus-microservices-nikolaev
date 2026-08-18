package ru.otus.hw.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Топология RabbitMQ USERService (app.rabbitmq.*):
 * producer — публикация UserCreatedEvent (users.events / user.created);
 * notification — best-effort уведомления (notifications.events / notification.event);
 * consumer — приём AccountCreatedEvent (accounts.events / user.account-activation.queue).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rabbitmq")
public class RabbitMQProperties {

    private ProducerProperties producer = new ProducerProperties();

    private NotificationProperties notification = new NotificationProperties();

    private ConsumerProperties consumer = new ConsumerProperties();

    @Getter
    @Setter
    public static class ProducerProperties {
        private String exchangeName = "users.events";

        private String routingKey = "user.created";
    }

    @Getter
    @Setter
    public static class NotificationProperties {
        private String exchangeName = "notifications.events";

        private String routingKey = "notification.event";
    }

    @Getter
    @Setter
    public static class ConsumerProperties {
        private String exchangeName = "accounts.events";

        private String queueName = "user.account-activation.queue";

        private String queueType = "classic";

        private String routingKey = "account.created";
    }
}
