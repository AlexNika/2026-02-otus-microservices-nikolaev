package ru.otus.hw.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Общие настройки безопасности всех микросервисов (префикс {@code app.security}).
 *
 * <p>{@code jwtSecretKey} - общий HMAC-секрет подписи access-токенов (env {@code JWT_SECRET_KEY});
 * пустое значение не допускается: {@link JwtTokenProvider} падает при старте (fail-fast).
 * {@code jwtAccessExpirationTime} - TTL access-токена в миллисекундах (по умолчанию 15 минут),
 * реальный источник значения - только AuthService (Issuer), остальным сервисам достаточно
 * локальной валидации подписи и claim {@code exp}.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.security")
public class JwtSecurityProperties {

    private String jwtSecretKey;

    private long jwtAccessExpirationTime = 900_000L;
}
