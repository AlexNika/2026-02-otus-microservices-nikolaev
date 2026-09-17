package ru.otus.hw.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает scheduled-вычитку auth_outbox ({@code OutboxPublisher}).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
