package ru.otus.hw.security;

import java.util.Optional;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Общий поставщик аудитора ({@code created_by}/{@code last_modified_by}) для всех микросервисов.
 *
 * <p>Поведение:
 * <ul>
 *   <li>пользовательский JWT-запрос (в SecurityContext лежит {@link AuthPrincipal}) -
 *       возвращается email пользователя (claim {@code sub});</li>
 *   <li>прочие аутентифицированные запросы (например, форма логина в AUTH) -
 *       возвращается {@code authentication.getName()};</li>
 *   <li>системные потоки - возвращается {@code "system"}: отсутствие {@code Authentication},
 *       неаутентифицированный токен, анонимный запрос (permitAll), внутренние вызовы по
 *       {@code X-Internal-API-Key} (фильтр ключа не устанавливает {@code Authentication})
 *       и консьюмеры RabbitMQ (вне HTTP-запроса контекст пуст).</li>
 * </ul>
 *
 * <p>Контракт отказоустойчивости: метод никогда не возвращает пустой {@code Optional}
 * и никогда не бросает исключение - аудитор всегда заполнен, сохранение сущности
 * не может упасть из-за аудита.
 */
@Component("auditorProvider")
public class SecurityAuditorAware implements AuditorAware<String> {

    private static final String SYSTEM_AUDITOR = "system";

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.of(SYSTEM_AUDITOR);
        }
        if (authentication.getPrincipal() instanceof AuthPrincipal authPrincipal
                && StringUtils.hasText(authPrincipal.email())) {
            return Optional.of(authPrincipal.email());
        }
        String name = authentication.getName();
        return StringUtils.hasText(name) ? Optional.of(name) : Optional.of(SYSTEM_AUDITOR);
    }
}
