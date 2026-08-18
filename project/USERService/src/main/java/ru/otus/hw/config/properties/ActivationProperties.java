package ru.otus.hw.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Параметры контроля активации биллинг-аккаунта (app.activation.*):
 * тайм-аут ожидания аккаунта после регистрации и период проверки PENDING-пользователей.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.activation")
public class ActivationProperties {

    /**
     * Сколько ждать появления биллинг-аккаунта; по истечении PENDING переходит в BLOCKED.
     */
    private Duration timeout = Duration.ofMinutes(30);

    /**
     * Период проверки PENDING-пользователей, миллисекунды.
     */
    private long checkIntervalMs = 300000L;
}
