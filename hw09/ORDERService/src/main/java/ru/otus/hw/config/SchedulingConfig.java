package ru.otus.hw.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Включает scheduled-задачи ORDERService: TTL-очистка ключей идемпотентности,
 * outbox-publisher, recovery сага-оркестратора.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
