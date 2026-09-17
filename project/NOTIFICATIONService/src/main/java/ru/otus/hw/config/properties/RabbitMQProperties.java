package ru.otus.hw.config.properties;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Топология RabbitMQ NOTIFICATIONService (app.rabbitmq.*):
 * плоская группа - приём NotificationEvent (notifications.events / notification.queue);
 * userSync - приём UserSyncEvent (user.sync.events / notification.profile-sync.queue).
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rabbitmq")
public class RabbitMQProperties {
    private String exchangeName;

    private String queueName;

    private String queueType;

    private String routingKey;

    private String consumerEnabled;

    private UserSyncProperties userSync = new UserSyncProperties();

    @Getter
    @Setter
    public static class UserSyncProperties {
        private String exchangeName = "user.sync.events";

        private String queueName = "notification.profile-sync.queue";

        private String queueType = "classic";

        private String routingKey = "user.profile.sync";
    }
}
