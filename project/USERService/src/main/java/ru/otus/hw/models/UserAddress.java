package ru.otus.hw.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.base.AuditableEntity;

/**
 * Адрес доставки пользователя (1:N к {@code users}). Source of truth - USERService;
 * полный снимок адресов публикуется в событии UserSyncEvent, DELIVERYService ведёт свою
 * read-модель по natural key {@code (user_id, source_address_id)}.
 *
 * <p>Аудит (created/updated/created_by/last_modified_by) наследуется от
 * {@link AuditableEntity}, как у остальных доменных сущностей проекта.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_addresses", indexes = {
        @Index(name = "idx_user_addresses_user_id", columnList = "user_id")
})
public class UserAddress extends AuditableEntity<Long> {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "full_address", nullable = false, columnDefinition = "TEXT")
    private String fullAddress;

    @Column(name = "city")
    private String city;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "delivery_preferences", columnDefinition = "TEXT")
    private String deliveryPreferences;
}
