package ru.otus.hw.config.properties;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Настройки refresh-токенов AuthService (префикс {@code app.refresh}):<br>
 * - TTL в днях (по умолчанию 30).
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.refresh")
public class RefreshTokenProperties {

    private int ttlDays = 30;

    @PostConstruct
    public void logProperties() {
        log.debug("Loaded RefreshTokenProperties: ttlDays={}", ttlDays);
    }
}
