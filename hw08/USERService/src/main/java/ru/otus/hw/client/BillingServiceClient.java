package ru.otus.hw.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.otus.hw.dto.AccountCreateDto;
import ru.otus.hw.exception.BillingServiceException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class BillingServiceClient {

    private final RestClient billingRestClient;

    /**
     * Creates a billing account for the specified user via BILLINGService.
     *
     * @param userId the ID of the user for whom to create an account
     * @throws BillingServiceException if the billing service call fails
     */
    public void createAccount(Long userId) {
        log.info("Creating billing account for user with ID: {}", userId);
        
        try {
            AccountCreateDto requestBody = new AccountCreateDto(userId);
            
            billingRestClient.post()
                    .uri("/internal/account")
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();
            
            log.info("Successfully created billing account for user with ID: {}", userId);
            
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
            
        } catch (Exception e) {
            String errorMessage = String.format(
                    "Unexpected error while creating billing account for user ID %d: %s",
                    userId,
                    e.getMessage()
            );
            log.error(errorMessage, e);
            throw new BillingServiceException(errorMessage, e);
        }
    }

    private String buildNetworkErrorMessage(Long userId, ResourceAccessException e) {
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
