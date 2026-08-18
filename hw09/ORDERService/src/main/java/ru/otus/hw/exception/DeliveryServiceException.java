package ru.otus.hw.exception;

import ru.otus.hw.models.OrderSagaState;

/**
 * Отказ шага саги при взаимодействии с DELIVERYService.
 */
public class DeliveryServiceException extends SagaStepException {

    public DeliveryServiceException(OrderSagaState.SagaStep step, String code, String message) {
        super(step, code, message);
    }

    public DeliveryServiceException(OrderSagaState.SagaStep step, String code, String message, Throwable cause) {
        super(step, code, message, cause);
    }
}
