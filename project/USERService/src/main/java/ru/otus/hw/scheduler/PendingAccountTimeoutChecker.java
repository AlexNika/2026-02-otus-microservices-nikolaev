package ru.otus.hw.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.otus.hw.client.BillingServiceClient;
import ru.otus.hw.config.properties.ActivationProperties;
import ru.otus.hw.models.AccountStatus;
import ru.otus.hw.models.User;
import ru.otus.hw.producer.NotificationEventPublisher;
import ru.otus.hw.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Контроль активации биллинг-аккаунтов: PENDING-пользователи старше тайм-аута проверяются
 * read-only запросом в BILLINGService. Аккаунт появился - ACTIVE (самовосстановление);
 * аккаунта нет (404) - BLOCKED ({@code locked=true}, логин запрещён); сбой проверки -
 * fail-open: пользователь пропускается и повторяется на следующем тике.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingAccountTimeoutChecker {

    private final UserRepository userRepository;

    private final BillingServiceClient billingServiceClient;

    private final NotificationEventPublisher notificationEventPublisher;

    private final ActivationProperties activationProperties;

    @Scheduled(fixedDelayString = "${app.activation.check-interval-ms:300000}")
    public void checkPendingUsers() {
        LocalDateTime cutoff = LocalDateTime.now().minus(activationProperties.getTimeout());
        List<User> pendingUsers =
                userRepository.findByAccountStatusAndCreatedBefore(AccountStatus.PENDING, cutoff);
        if (pendingUsers.isEmpty()) {
            return;
        }
        log.info("Activation check: {} PENDING user(s) older than {}", pendingUsers.size(),
                activationProperties.getTimeout());
        for (User user : pendingUsers) {
            try {
                processUser(user);
            } catch (RuntimeException e) {
                log.warn("Activation check failed for userId={}, skipping (fail-open, retry on next tick)",
                        user.getId(), e);
            }
        }
    }

    private void processUser(@NonNull User user) {
        if (billingServiceClient.accountExists(user.getId())) {
            user.setAccountStatus(AccountStatus.ACTIVE);
            user.setLocked(false);
            userRepository.save(user);
            log.info("Billing account found after registration: userId={} activated (self-healing)",
                    user.getId());
            notificationEventPublisher.publish(user.getId(), "ACCOUNT_ACTIVATED",
                    "Ваш аккаунт полностью активирован");
        } else {
            user.setAccountStatus(AccountStatus.BLOCKED);
            user.setLocked(true);
            userRepository.save(user);
            log.error("Billing account was not created within the timeout: userId={} BLOCKED", user.getId());
            notificationEventPublisher.publish(user.getId(), "ACCOUNT_CREATION_FAILED",
                    "биллинг-аккаунт не создан за тайм-аут");
        }
    }
}
