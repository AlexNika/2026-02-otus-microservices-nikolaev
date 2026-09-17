package ru.otus.hw.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Проверка ownership для {@code @PreAuthorize}-выражений: «владелец ресурса или ADMIN».
 *
 * <p>Используется в спелах как {@code @authz.ownerOrAdmin(...)}:
 * <pre>
 * &#64;PreAuthorize("@authz.ownerOrAdmin(#userId)")
 * </pre>
 *
 * <p>Контракт: берёт {@code SecurityContextHolder.getContext().getAuthentication()};
 * при {@code null}/неаутентифицированном контексте возвращает {@code false} (защита от NPE).
 * Иначе кастит {@code authentication.getPrincipal()} к {@link AuthPrincipal}
 * (см. {@link JwtAuthenticationFilter} - principal лежит в {@code Authentication.principal})
 * и сравнивает {@code userId}; для ADMIN ({@link AuthPrincipal#isAdmin()}) возвращает true.
 */
@Component("authz")
public class OwnershipChecker {

    public boolean ownerOrAdmin(Long userId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof AuthPrincipal authPrincipal)) {
            return false;
        }
        return authPrincipal.isAdmin() || (userId != null && userId.equals(authPrincipal.userId()));
    }
}
