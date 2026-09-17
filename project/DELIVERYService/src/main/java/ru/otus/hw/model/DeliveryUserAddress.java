package ru.otus.hw.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.base.AuditableEntity;

import java.time.Instant;

/**
 * Read-модель адресов доставки пользователя, реплицируемая из USERService событием
 * UserSyncEvent (канал USER → DELIVERY, user.sync.events / user.profile.sync).
 *
 * <p>Natural key - {@code (user_id, source_address_id)}: {@code source_address_id} это id
 * адреса в {@code user_addresses} USERService (source of truth). Полный снимок применяется
 * идемпотентно: upsert по natural key + удаление отсутствующих в снимке записей.
 *
 * <p>{@code updatedAt} - время события-источника (timestamp guard против out-of-order
 * доставки); аудита-колонки наследуются от {@link AuditableEntity}.
 */
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "delivery_user_addresses",
        uniqueConstraints = @UniqueConstraint(name = "uk_delivery_source_address",
                columnNames = {"user_id", "source_address_id"}),
        indexes = {
                @Index(name = "idx_delivery_addresses_user_id", columnList = "user_id")
        })
public class DeliveryUserAddress extends AuditableEntity<Long> {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "source_address_id", nullable = false)
    private Long sourceAddressId;

    @Column(name = "full_address", nullable = false, columnDefinition = "TEXT")
    private String fullAddress;

    @Column(name = "city")
    private String city;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "preferences", columnDefinition = "TEXT")
    private String preferences;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
