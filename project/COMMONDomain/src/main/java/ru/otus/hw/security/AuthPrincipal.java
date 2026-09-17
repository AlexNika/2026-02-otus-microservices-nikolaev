package ru.otus.hw.security;

import java.util.List;

/**
 * Principal, который JWT-фильтр кладёт в SecurityContext всех микросервисов.
 *
 * <p>Данные берутся из claims access-токена (локальная валидация подписи, без обращений в БД):
 * {@code userId} - идентификатор пользователя (он же ключ ресурса во всех сервисах),
 * {@code email} - claim {@code sub}, {@code roles} - роли из токена.
 */
public record AuthPrincipal(Long userId, String email, List<String> roles) {

    public boolean isAdmin() {
        return roles != null && roles.contains("ADMIN");
    }
}
