package ru.otus.hw.config.properties;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AuthService-специфичные настройки безопасности (префикс {@code app.security}):<br>
 * - сила BCrypt для password_hash<br>
 * - JWT-секрет и TTL access-токена берёт общий {@link ru.otus.hw.security.JwtSecurityProperties} из COMMONDomain
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.security")
public class AuthSecurityProperties {

    private int bcryptIterations = 12;

    @PostConstruct
    public void logProperties() {
        log.debug("Loaded AuthSecurityProperties: bcryptIterations={}", bcryptIterations);
    }
}
