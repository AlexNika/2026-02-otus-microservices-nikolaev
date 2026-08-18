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
@ConfigurationProperties(prefix = "app.saga.recovery")
public class SagaRecoveryProperties implements SagaRecoveryConfig {

    /**
     * Сага считается "зависшей" (кандидат на recovery), если не было прогресса дольше этого срока.
     */
    @Getter(onMethod = @__(@Override))
    private Duration staleAfter;

    @Getter(onMethod = @__(@Override))
    private Duration interval;
}
