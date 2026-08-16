package ru.otus.hw.config.properties;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Slf4j
@Setter
@Component
@ConfigurationProperties(prefix = "app.rabbitmq")
public class RabbitMQProperties implements RabbitMQConfig {

    @Getter(onMethod = @__(@Override))
    private String exchangeName;

    @Getter(onMethod = @__(@Override))
    private String queueName;

    @Getter
    private String queueType;

    @Getter(onMethod = @__(@Override))
    private String routingKey;

    @Getter
    private String consumerEnabled;
}
