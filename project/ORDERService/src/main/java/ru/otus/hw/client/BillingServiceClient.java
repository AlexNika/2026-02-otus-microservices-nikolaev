package ru.otus.hw.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.dto.RefundRequestDto;
import ru.otus.hw.dto.WithdrawRequestDto;
import ru.otus.hw.dto.WithdrawStatusDto;
import ru.otus.hw.exception.BillingServiceException;
import ru.otus.hw.exception.ErrorCodes;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * Клиент внутреннего API BILLINGService для шагов саги создания заказа.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingServiceClient {

    private static final String SERVICE_NAME = "BILLINGService";

    private final RestClient billingRestClient;

    private final ObjectMapper objectMapper;

    /**
     * Снимает средства со счета пользователя через BILLINGService для оплаты заказа.
     *
     * @param userId   the ID of the user
     * @param amount   the amount to withdraw
     * @param orderId  the ID of the order
     * @throws BillingServiceException if the billing service call fails
     */
    public void withdrawFunds(Long userId, java.math.BigDecimal amount, Long orderId) {
        log.info("Withdrawing funds for user ID: {}, amount: {}, order ID: {}", userId, amount, orderId);
        
        try {
            WithdrawRequestDto requestBody = new WithdrawRequestDto(userId, orderId, amount);
            
            billingRestClient.post()
                    .uri("/internal/order/withdraw")
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();
            
        } catch (ResourceAccessException e) {
            String errorMessage = buildNetworkErrorMessage(userId, e);
            log.error(errorMessage, e);
            throw new BillingServiceException(errorMessage, e, true, null, null);
            
        } catch (RestClientResponseException e) {
            throw downstreamError(e, String.format(
                    "Billing service returned error for user ID %d", userId));
        }
    }

    /**
     * Возврат средств на пользовательский счет через BILLINGService за отмененный заказ.
     *
     * @param userId   the ID of the user
     * @param amount   the amount to refund
     * @param orderId  the ID of the order
     * @throws BillingServiceException if the billing service call fails
     */
    public void refundFunds(Long userId, java.math.BigDecimal amount, Long orderId) {
        log.info("Refunding funds for user ID: {}, amount: {}, order ID: {}", userId, amount, orderId);
        
        try {
            RefundRequestDto requestBody = new RefundRequestDto(userId, orderId, amount);
            
            billingRestClient.post()
                    .uri("/internal/order/refund")
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();
            
        } catch (ResourceAccessException e) {
            String errorMessage = buildNetworkErrorMessage(userId, e);
            log.error(errorMessage, e);
            throw new BillingServiceException(errorMessage, e, true, null, null);
            
        } catch (RestClientResponseException e) {
            throw downstreamError(e, String.format(
                    "Billing service returned error for user ID %d", userId));
        }
    }

    /**
     * Read-only запрос статуса списания по заказу (без побочных эффектов).
     * Используется recovery сага-оркестратора для восстановления фактического состояния.
     *
     * @param orderId the ID of the order
     * @return withdrawal status for the order
     * @throws BillingServiceException if the billing service call fails
     */
    public WithdrawStatusDto getWithdrawStatus(Long orderId) {
        log.info("Fetching withdrawal status for order ID: {}", orderId);

        try {
            return billingRestClient.get()
                    .uri("/internal/order/{orderId}/withdraw-status", orderId)
                    .retrieve()
                    .body(WithdrawStatusDto.class);

        } catch (ResourceAccessException e) {
            String errorMessage = buildNetworkErrorMessage(orderId, e);
            log.error(errorMessage, e);
            throw new BillingServiceException(errorMessage, e, true, null, null);

        } catch (RestClientResponseException e) {
            throw downstreamError(e, String.format(
                    "Billing service returned error for order ID %d", orderId));
        }
    }

    /**
     * Классификация downstream-ошибки BILLINGService: извлечение машинного кода через
     * {@link DownstreamErrors} (как warehouse/delivery) и признак транзитности для ретраев саги.
     * Транзитные: 5xx и 409 CONCURRENT_MODIFICATION; бизнес-отказы (INSUFFICIENT_FUNDS и др.)
     * и прочие 4xx - нетранзитные, шаг сразу ведёт к компенсации.
     */
    private @NonNull BillingServiceException downstreamError(@NonNull RestClientResponseException e,
                                                             String context) {
        String code = DownstreamErrors.extractCode(e, objectMapper);
        String message = String.format("%s: %s", context, DownstreamErrors.describe(e, SERVICE_NAME));
        boolean transientError = e.getStatusCode().is5xxServerError()
                || ErrorCodes.CONCURRENT_MODIFICATION.equals(code);
        log.error(message);
        return new BillingServiceException(message, e, transientError, code, e.getStatusCode().value());
    }

    private @NonNull String buildNetworkErrorMessage(Long userId, @NonNull ResourceAccessException e) {
        Throwable cause = e.getCause();
        
        if (cause instanceof ConnectException) {
            return String.format(
                    "Billing service is unavailable (connection refused) for user ID %d. " +
                    "Check if BILLINGService is running and the URL is configured correctly in application.yaml",
                    userId
            );
        }
        
        if (cause instanceof SocketTimeoutException) {
            return String.format(
                    "Billing service request timed out for user ID %d. " +
                    "The service may be overloaded or not responding",
                    userId
            );
        }
        
        return String.format(
                "Network error while calling billing service for user ID %d: %s",
                userId,
                e.getMessage()
        );
    }
}
