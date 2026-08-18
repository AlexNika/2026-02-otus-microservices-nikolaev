package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Read-only статус списания по заказу в BILLINGService
 * (GET /internal/order/{orderId}/withdraw-status). Используется recovery сага-оркестратора
 * для восстановления фактического состояния без побочных эффектов.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WithdrawStatusDto(
        Long orderId,
        boolean withdrawn,
        Long transactionId,
        BigDecimal amount) {
}
