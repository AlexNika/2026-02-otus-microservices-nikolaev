package ru.otus.hw.exception;

import lombok.Getter;

@Getter
public class CourierAssignmentException extends RuntimeException {

    private final String code;

    private CourierAssignmentException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static CourierAssignmentException courierAssignmentFailed(Long slotId, int capacity) {
        String detailed = "No free courier number found for slot id: " + slotId
                + ", capacity: " + capacity + " — should be unreachable after reserved/count check";
        return new CourierAssignmentException(ErrorCodes.DELIVERY_COURIER_ASSIGNMENT_FAILED, detailed);
    }
}
