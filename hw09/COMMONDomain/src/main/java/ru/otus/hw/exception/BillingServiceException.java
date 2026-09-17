package ru.otus.hw.exception;

import lombok.Getter;

/**
 * Ошибка взаимодействия с BILLINGService.
 *
 * <p>Идемпотентности несёт машинно-читаемый код downstream-ошибки,
 * HTTP-статус и признак транзитности для классификации ретраев сага-оркестратором:
 * транзитные (5xx, тайм-ауты, CONCURRENT_MODIFICATION) ретраятся, бизнес-отказы
 * (INSUFFICIENT_FUNDS и др.) ведут сразу к компенсации.
 */
@Getter
public class BillingServiceException extends RuntimeException {

    private final String code;

    private final Integer statusCode;

    /**
     * Флаг транзитности ошибки.<br>
     * --- GETTER ---<br>
     * Транзитная ошибка (5xx, тайм-аут, CONCURRENT_MODIFICATION) — шаг саги можно повторить.
     */
    private final boolean transientError;

    public BillingServiceException(String message) {
        this(message, null, false, null, null);
    }

    public BillingServiceException(String message, Throwable cause) {
        this(message, cause, false, null, null);
    }

    public BillingServiceException(String message, Throwable cause, boolean transientError,
                                   String code, Integer statusCode) {
        super(message, cause);
        this.transientError = transientError;
        this.code = code;
        this.statusCode = statusCode;
    }

}
