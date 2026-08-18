package ru.otus.hw.exception;

import lombok.Getter;
import ru.otus.hw.models.OrderSagaState;

/**
 * Базовое исключение шага саги создания заказа. Несёт идентификатор шага, на котором произошёл
 * отказ, и машинно-читаемый код ошибки downstream-сервиса (из {@link ru.otus.hw.dto.ErrorDto}).
 */
@Getter
public abstract class SagaStepException extends RuntimeException {

    private final OrderSagaState.SagaStep step;

    private final String code;

    protected SagaStepException(OrderSagaState.SagaStep step, String code, String message) {
        super(message);
        this.step = step;
        this.code = code;
    }

    protected SagaStepException(OrderSagaState.SagaStep step, String code, String message, Throwable cause) {
        super(message, cause);
        this.step = step;
        this.code = code;
    }
}
