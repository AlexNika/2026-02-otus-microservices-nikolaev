package ru.otus.hw.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Топология RabbitMQ BILLINGService (app.rabbitmq.*):
 * consumer - приём UserCreatedEvent (users.events / billing.account-create.queue);
 * producer - публикация AccountCreatedEvent (accounts.events / account.created);
 * notification - best-effort уведомления (notifications.events / notification.event).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rabbitmq")
public class RabbitMQProperties {

    private ConsumerProperties consumer = new ConsumerProperties();

    private ProducerProperties producer = new ProducerProperties();

    private NotificationProperties notification = new NotificationProperties();

    @Getter
    @Setter
    public static class ConsumerProperties {
        private String exchangeName = "users.events";

        private String queueName = "billing.account-create.queue";

        private String queueType = "classic";

        private String routingKey = "user.created";
    }

    @Getter
    @Setter
    public static class ProducerProperties {
        private String exchangeName = "accounts.events";

        private String routingKey = "account.created";
    }

    @Getter
    @Setter
    public static class NotificationProperties {
        private String exchangeName = "notifications.events";

        private String routingKey = "notification.event";
    }
}
