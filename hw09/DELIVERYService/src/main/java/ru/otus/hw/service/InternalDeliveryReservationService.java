package ru.otus.hw.service;

import ru.otus.hw.dto.CancelDeliveryResponse;
import ru.otus.hw.dto.DeliveryReservationResponse;
import ru.otus.hw.dto.ReserveDeliveryRequest;

/**
 * Сервис резервирования курьеров для межсервисного взаимодействия (/internal/**).
 * Шаг саги из OrderService.
 */
public interface InternalDeliveryReservationService {

    /**
     * Резервирует доставку для заказа. Идемпотентно по orderId.
     */
    ReserveDeliveryResult reserve(ReserveDeliveryRequest request);

    /**
     * Подтверждает резерв по orderId. Идемпотентно.
     */
    DeliveryReservationResponse confirm(Long orderId);

    /**
     * Компенсация: отменяет резерв по orderId. Идемпотентно и безопасно для повторных вызовов.
     */
    CancelDeliveryResponse cancel(Long orderId);

    DeliveryReservationResponse getByOrderId(Long orderId);

    DeliveryReservationResponse getById(Long reservationId);
}
