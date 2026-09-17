package ru.otus.hw.config.properties;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.idempotency")
public class IdempotencyProperties implements IdempotencyConfig {

    @Getter(onMethod = @__(@Override))
    private Duration ttl;

    @Getter(onMethod = @__(@Override))
    private Duration cleanupInterval;
}
