package ru.otus.hw.exception;

import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;

@Getter
public class DeliveryReservationException extends RuntimeException {

    private final String code;

    private DeliveryReservationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static DeliveryReservationException noFreeCourier(LocalDate date, LocalTime start, LocalTime end,
                                                             int capacity, long reserved) {
        String message = "No free courier for date: " + date
                + ", slot: " + start + "-" + end
                + ", capacity: " + capacity
                + ", reserved: " + reserved;
        return new DeliveryReservationException(ErrorCodes.DELIVERY_NO_FREE_COURIER, message);
    }

    public static DeliveryReservationException capacityConflict(String message) {
        return new DeliveryReservationException(ErrorCodes.DELIVERY_CAPACITY_CONFLICT, message);
    }

    public static DeliveryReservationException stateConflict(Long orderId, String currentStatus, String action) {
        String message = "Cannot " + action + " delivery reservation for order id: " + orderId
                + " in status " + currentStatus;
        return new DeliveryReservationException(ErrorCodes.DELIVERY_RESERVATION_STATE_CONFLICT, message);
    }
}
