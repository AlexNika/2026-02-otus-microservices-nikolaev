package ru.otus.hw.config.properties;

public interface RabbitMQConfig {

    String getExchangeName();

    String getQueueName();

    String getRoutingKey();
}
