package ru.otus.hw.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.otus.hw.dto.UserAddressDto;
import ru.otus.hw.dto.UserCreatedEvent;
import ru.otus.hw.models.AccountStatus;
import ru.otus.hw.models.User;
import ru.otus.hw.models.UserAddress;
import ru.otus.hw.models.UserProfile;
import ru.otus.hw.producer.UserSyncEventPublisher;
import ru.otus.hw.repository.UserAddressRepository;
import ru.otus.hw.repository.UserRepository;
import ru.otus.hw.service.UserSyncEventAssembler;
import ru.otus.hw.tracing.W3CTraceContextAdapter;

import java.util.List;
import java.util.Map;

/**
 * Приём UserCreatedEvent (AUTH → USER): AuthService сохранил credentials и задал userId -
 * USERService создаёт проекцию: {@code users} (id из события, НЕ автоинкремент),
 * {@code user_profile} (userName/firstName/lastName/birthdate/phone) и {@code user_addresses}
 * (1:N, {@code isDefault}), затем публикует {@code UserSyncEvent} как раньше.
 *
 * <p>Идемпотентность по natural key {@code userId} (PK users.id): повторная доставка
 * события для уже созданного пользователя не создаёт дублей, только переопубликует
 * UserSyncEvent (потребители идемпотентны).
 *
 * <p>Известная гонка (зафиксирована, не устраняется синхронными вызовами): профиль появляется
 * асинхронно (Outbox → RabbitMQ → консьюмер), {@code GET /api/v1/profile} в этом окне вернёт
 * 404 - клиент обрабатывает коротким retry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserCreatedEventConsumer {

    private final UserRepository userRepository;

    private final UserAddressRepository userAddressRepository;

    private final UserSyncEventPublisher userSyncEventPublisher;

    private final UserSyncEventAssembler userSyncEventAssembler;

    private final W3CTraceContextAdapter traceContextAdapter;

    @RabbitListener(queues = "${app.rabbitmq.user-created.queue-name:user.user-created.queue}")
    @Transactional
    public void handleUserCreated(@NonNull UserCreatedEvent event, @Headers Map<String, Object> amqpHeaders) {
        try (W3CTraceContextAdapter.Scope ignored =
                traceContextAdapter.open(amqpHeaders, "user.user-created.consume")) {
            log.info("Received UserCreatedEvent: userId={}, eventId={}", event.userId(), event.eventId());

            if (event.userId() == null) {
                log.error("UserCreatedEvent without userId, eventId={} - cannot create projection",
                        event.eventId());
                return;
            }

            if (userRepository.existsById(event.userId())) {
                log.info("Projection for userId={} already exists, replay - republishing UserSyncEvent only",
                        event.userId());
            } else {
                createProjection(event);
            }

            userSyncEventPublisher.send(userSyncEventAssembler.assemble(event.userId()));
            log.info("UserCreatedEvent processed: userId={}", event.userId());
        }
    }

    private void createProjection(@NonNull UserCreatedEvent event) {
        UserProfile profile = UserProfile.builder()
                .userName(event.userName())
                .firstName(event.firstName())
                .lastName(event.lastName())
                .birthdate(event.birthdate())
                .phone(event.phone())
                .build();

        User user = User.builder()
                .id(event.userId())
                .email(event.email())
                .profile(profile)
                .accountStatus(AccountStatus.PENDING)
                .build();
        userRepository.save(user);

        saveAddresses(event.userId(), event.addresses());
        log.info("Identity projection created for userId={}, email={}", event.userId(), event.email());
    }

    private void saveAddresses(@NonNull Long userId, List<UserAddressDto> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return;
        }
        List<UserAddress> entities = addresses.stream()
                .map(dto -> UserAddress.builder()
                        .userId(userId)
                        .fullAddress(dto.fullAddress())
                        .city(dto.city())
                        .postalCode(dto.postalCode())
                        .isDefault(dto.isDefault() != null ? dto.isDefault() : Boolean.FALSE)
                        .deliveryPreferences(dto.deliveryPreferences())
                        .build())
                .toList();
        userAddressRepository.saveAll(entities);
    }
}
