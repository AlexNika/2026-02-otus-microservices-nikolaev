package ru.otus.hw.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает scheduled-задачи USERService: outbox-publisher (UserCreatedEvent)
 * и проверку активации биллинг-аккаунтов (PENDING → ACTIVE/BLOCKED).
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
