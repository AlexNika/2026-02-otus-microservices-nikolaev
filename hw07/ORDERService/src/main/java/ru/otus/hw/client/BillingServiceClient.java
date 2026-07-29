package ru.otus.hw.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.dto.RefundRequestDto;
import ru.otus.hw.dto.WithdrawRequestDto;
import ru.otus.hw.exception.BillingServiceException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingServiceClient {

    private final RestClient billingRestClient;

    /**
     * Withdraws funds from user's account via BILLINGService for an order.
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
            throw new BillingServiceException(errorMessage, e);
            
        } catch (RestClientResponseException e) {
            String errorMessage = String.format(
                    "Billing service returned error for user ID %d: status=%d, response=%s",
                    userId,
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString()
            );
            log.error(errorMessage);
            throw new BillingServiceException(errorMessage, e);
        }
    }

    /**
     * Refunds funds back to user's account via BILLINGService for a canceled order.
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
            throw new BillingServiceException(errorMessage, e);
            
        } catch (RestClientResponseException e) {
            String errorMessage = String.format(
                    "Billing service returned error for user ID %d: status=%d, response=%s",
                    userId,
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString()
            );
            log.error(errorMessage);
            throw new BillingServiceException(errorMessage, e);
        }
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
