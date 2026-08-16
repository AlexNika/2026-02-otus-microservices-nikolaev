package ru.otus.hw.config.properties;

import java.time.Duration;

public interface SagaRecoveryConfig {

    Duration getStaleAfter();

    Duration getInterval();
}
