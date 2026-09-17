package ru.otus.hw.config;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Фабрика listener-контейнера USERService (приём AccountCreatedEvent).
 *
 * <p>Политика повторных попыток: до {@value #MAX_DELIVERY_ATTEMPTS} попыток с экспоненциальным
 * backoff; после исчерпания {@link RejectAndDontRequeueRecoverer} отклоняет сообщение без requeue,
 * и благодаря {@code defaultRequeueRejected(false)} + DLX-аргументам очереди
 * (см. {@link RabbitMQConsumerConfig#accountActivationQueue()}) ядовитое сообщение уходит в DLQ,
 * а не бесконечно переотправляется и не молча выбрасывается.
 */
@Configuration
public class RabbitMQListenerConfig {

    /**
     * Максимум попыток обработки сообщения consumer'ом (включая первую).
     */
    private static final int MAX_DELIVERY_ATTEMPTS = 3;

    private static final long BACKOFF_INITIAL_INTERVAL_MS = 500;

    private static final double BACKOFF_MULTIPLIER = 2.0;

    private static final long BACKOFF_MAX_INTERVAL_MS = 2000;

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false);

        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);

        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(MAX_DELIVERY_ATTEMPTS)
                .backOffOptions(BACKOFF_INITIAL_INTERVAL_MS, BACKOFF_MULTIPLIER, BACKOFF_MAX_INTERVAL_MS)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());

        return factory;
    }
}
