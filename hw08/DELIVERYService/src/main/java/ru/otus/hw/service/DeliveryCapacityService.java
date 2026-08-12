package ru.otus.hw.service;

import ru.otus.hw.dto.CapacityResponse;
import ru.otus.hw.dto.SetCourierCapacityRequest;

import java.time.LocalDate;

/**
 * Сервис управления ёмкостью курьеров по датам (admin API).
 */
public interface DeliveryCapacityService {

    /**
     * Установка количества курьеров и списка слотов на дату.
     * Если слоты не переданы, используются default-слоты из конфигурации.
     */
    CapacityResponse setCapacity(LocalDate date, SetCourierCapacityRequest request);

    /**
     * Получение настроенной ёмкости курьеров на дату.
     */
    CapacityResponse getCapacity(LocalDate date);
}
