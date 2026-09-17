package ru.otus.hw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Событие канала AUTH → USER/BILLING: пользователь зарегистрирован в AuthService
 * (credentials сохранены), требуется создать проекции данных в потребителях.
 *
 * <p>Источник - AuthService (единственный Issuer credentials): сохраняет
 * email/password_hash/роли и публикует событие через свой Outbox. Потребители:
 * USERService (проекция {@code users} + профиль + адреса, затем рассылает
 * {@link UserSyncEvent}) и BILLINGService (создание счёта - существующий консьюмер,
 * контракт по {@code userId} сохраняется, профильные поля игнорирует).
 *
 * <p>{@code eventId} - ключ идемпотентности сообщения (UUID-строка, один на событие):
 * BILLING использует его как eventId уведомления ACCOUNT_CREATED, NOTIFICATION дедуплицирует
 * повторные доставки по нему. Идемпотентность самой обработки - по natural key {@code userId}
 * (unique accounts.user_id).
 *
 * <p>Профильные поля ({@code userName..addresses}) расширенного контракта несёт та же
 * шина: {@code userId} задаёт AuthService, USERService использует его как PK своих таблиц.
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserCreatedEvent(
        String eventId,
        Long userId,
        String email,
        String userName,
        String firstName,
        String lastName,
        LocalDateTime birthdate,
        String phone,
        List<UserAddressDto> addresses,
        Instant timestamp) {
}
