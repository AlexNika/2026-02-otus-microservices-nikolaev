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

import java.time.LocalDateTime;

/**
 * Refresh-токен пользователя: сам opaque UUID клиенту в БД не попадает -
 * хранится только SHA-256 хэш ({@code token_hash}, 64 hex-символа).
 *
 * <p>{@code rotated = true} означает, что токен уже ротирован при refresh:
 * повторное предъявление такого хэша - reuse, по которому удаляются ВСЕ
 * refresh-токены пользователя.
 */
@Getter
@Setter
@Builder
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "refresh_tokens", indexes = {
        @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id")
})
public class RefreshToken extends AuditableEntity<Long> {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "rotated", nullable = false)
    @Builder.Default
    private Boolean rotated = Boolean.FALSE;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
