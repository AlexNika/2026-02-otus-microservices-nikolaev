package ru.otus.hw.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.otus.hw.exception.BillingServiceException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * Read-only клиент BILLINGService: создание аккаунтов идёт событийной хореографией
 * (UserCreatedEvent через outbox), клиент используется только тайм-аут проверкой
 * активации (появился ли аккаунт для PENDING-пользователя).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingServiceClient {

    private final RestClient billingRestClient;

    /**
     * Проверяет наличие биллинг-аккаунта пользователя: GET /api/v1/account/user/{userId}.
     *
     * @param userId the ID of the user to check
     * @return true если аккаунт есть (200), false если его нет (404)
     * @throws BillingServiceException при сетевых сбоях и прочих HTTP-ошибках
     */
    public boolean accountExists(Long userId) {
        log.info("Checking billing account existence for user with ID: {}", userId);

        try {
            billingRestClient.get()
                    .uri("/api/v1/account/user/{userId}", userId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == HttpStatus.NOT_FOUND.value()) {
                            throw new AccountNotFoundException();
                        }
                        throw new BillingServiceException(String.format(
                                "Billing service returned error while checking account for user ID %d: status=%d",
                                userId, status));
                    })
                    .toBodilessEntity();

            log.info("Billing account exists for user with ID: {}", userId);
            return true;

        } catch (AccountNotFoundException e) {
            log.info("No billing account for user with ID: {}", userId);
            return false;

        } catch (BillingServiceException e) {
            throw e;

        } catch (ResourceAccessException e) {
            String errorMessage = buildNetworkErrorMessage(userId, e);
            log.error(errorMessage, e);
            throw new BillingServiceException(errorMessage, e);

        } catch (Exception e) {
            String errorMessage = String.format(
                    "Unexpected error while checking billing account for user ID %d: %s",
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

    /**
     * Маркер 404 из onStatus-обработчика: аккаунт не найден штатно, это не ошибка.
     */
    private static final class AccountNotFoundException extends RuntimeException {
        private AccountNotFoundException() {
            super();
        }
    }
}
