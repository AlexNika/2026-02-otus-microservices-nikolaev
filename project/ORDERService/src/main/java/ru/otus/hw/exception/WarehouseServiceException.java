package ru.otus.hw.exception;

import ru.otus.hw.models.OrderSagaState;

/**
 * Отказ шага саги при взаимодействии с WAREHOUSEService.
 */
public class WarehouseServiceException extends SagaStepException {

    public WarehouseServiceException(OrderSagaState.SagaStep step, String code, String message) {
        super(step, code, message);
    }

    public WarehouseServiceException(OrderSagaState.SagaStep step, String code, String message, Throwable cause) {
        super(step, code, message, cause);
    }
}
