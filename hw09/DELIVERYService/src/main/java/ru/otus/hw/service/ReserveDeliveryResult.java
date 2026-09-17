package ru.otus.hw.service;

import ru.otus.hw.dto.DeliveryReservationResponse;

/**
 * Результат резервирования доставки.
 *
 * @param response DTO резерва
 * @param created  true - резерв создан впервые (201), false - идемпотентный повтор (200)
 */
public record ReserveDeliveryResult(
        DeliveryReservationResponse response,
        boolean created
) {
}
