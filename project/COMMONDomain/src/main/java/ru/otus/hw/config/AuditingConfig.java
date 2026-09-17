package ru.otus.hw.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Единственная конфигурация JPA-аудита для всех микросервисов (живёт в COMMONDomain).
 *
 * <p>Включает аудит и привязывает его к общему поставщику аудитора -
 * бину {@code auditorProvider} ({@link ru.otus.hw.security.SecurityAuditorAware}),
 * который читает текущего актёра из {@code SecurityContextHolder}.
 * Пер-сервисные конфигурации аудита удалены: двойное включение
 * {@code @EnableJpaAuditing} недопустимо (конфликт регистрации {@code jpaAuditingHandler}).
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class AuditingConfig {
}
