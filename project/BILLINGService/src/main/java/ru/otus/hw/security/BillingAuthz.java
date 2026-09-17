package ru.otus.hw.security;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Ownership-проверки BILLINGService для {@code @PreAuthorize}-выражений
 * (доступен в спелах как {@code @billingAuthz}).
 *
 * <p>Self-доступ по пути {@code /user/{userId}}, {@code /{userId}/deposit},
 * {@code /{userId}/withdraw}: текущий пользователь - это {@code userId} или ADMIN.
 * Проверка владельца по accountId ({@code GET /api/v1/account/{id}}) выполняется
 * через {@code @PostAuthorize("@authz.ownerOrAdmin(returnObject?.body?.userId())")}.
 */
@Component("billingAuthz")
@RequiredArgsConstructor
public class BillingAuthz {

    private final OwnershipChecker ownershipChecker;

    /**
     * Текущий пользователь - это {@code userId} или ADMIN.
     */
    public boolean selfOrAdmin(@NonNull Long userId) {
        return ownershipChecker.ownerOrAdmin(userId);
    }
}
