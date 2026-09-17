package ru.otus.hw.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Топология RabbitMQ DELIVERYService (app.rabbitmq.*):
 * userSync - приём UserSyncEvent (user.sync.events / delivery.profile-sync.queue).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.rabbitmq")
public class RabbitMQProperties {

    private UserSyncProperties userSync = new UserSyncProperties();

    @Getter
    @Setter
    public static class UserSyncProperties {
        private String exchangeName = "user.sync.events";

        private String queueName = "delivery.profile-sync.queue";

        private String queueType = "classic";

        private String routingKey = "user.profile.sync";
    }
}
