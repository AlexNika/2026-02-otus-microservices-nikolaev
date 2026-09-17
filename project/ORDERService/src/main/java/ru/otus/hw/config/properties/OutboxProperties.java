package ru.otus.hw.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Настройки scheduled-очистки {@code notification_outbox}: интервал запуска джоба и период
 * хранения доставленных (SENT) событий.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxProperties {

    private Duration cleanupInterval = Duration.ofHours(1);

    private Duration retention = Duration.ofDays(7);
}
