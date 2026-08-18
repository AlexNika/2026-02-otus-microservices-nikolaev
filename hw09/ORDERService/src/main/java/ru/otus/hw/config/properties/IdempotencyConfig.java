package ru.otus.hw.config.properties;

import java.time.Duration;

public interface IdempotencyConfig {

    Duration getTtl();

    Duration getCleanupInterval();
}
