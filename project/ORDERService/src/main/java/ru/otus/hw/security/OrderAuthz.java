package ru.otus.hw.security;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import ru.otus.hw.models.Order;
import ru.otus.hw.repository.OrderRepository;

/**
 * Ownership-проверки ORDERService для {@code @PreAuthorize}-выражений
 * (доступен в спелах как {@code @orderAuthz}). Владелец заказа определяется
 * по локальным данным ({@code orders.user_id}), без межсервисных вызовов.
 */
@Component("orderAuthz")
@RequiredArgsConstructor
public class OrderAuthz {

    private final OrderRepository orderRepository;

    private final OwnershipChecker ownershipChecker;

    /**
     * Текущий пользователь - владелец заказа {@code orderId} или ADMIN.
     * Для несуществующего заказа возвращает false (дальше отработает 404 сервиса).
     */
    public boolean orderOwner(@NonNull Long orderId) {
        return orderRepository.findById(orderId)
                .map(Order::getUserId)
                .map(ownershipChecker::ownerOrAdmin)
                .orElse(false);
    }
}
