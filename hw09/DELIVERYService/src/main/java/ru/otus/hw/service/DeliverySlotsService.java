package ru.otus.hw.service;

import ru.otus.hw.dto.AvailableSlotsResponse;

import java.time.LocalDate;

/**
 * Сервис доступных слотов доставки (публичное API).
 */
public interface DeliverySlotsService {

    /**
     * Список временных слотов на дату с признаком доступности.
     * Если на дату нет настроенных слотов, возвращается ответ с пустым списком слотов.
     */
    AvailableSlotsResponse getAvailableSlots(LocalDate date);
}
