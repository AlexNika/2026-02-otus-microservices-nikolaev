package ru.otus.hw.dto;

/**
 * Статус резервирования товара на складе. Общий для WAREHOUSEService и ORDERService,
 * wire-формат — имя константы (RESERVED/CONFIRMED/RELEASED/FAILED).
 */
public enum ReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED,
    FAILED
}
