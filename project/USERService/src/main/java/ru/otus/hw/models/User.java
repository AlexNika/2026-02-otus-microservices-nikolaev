package ru.otus.hw.models;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedEntityGraphs;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Проекция идентифичности пользователя в USERService: credentials (email/password/роли)
 * живут только в AuthService, здесь - тот же {@code userId} как PK плюс профиль и адреса.
 *
 * <p>{@code id} задаётся из {@code UserCreatedEvent} (значение присваивает AuthService),
 * поэтомуGenerationStrategy не используется - идентификатор assigned. По этой же причине
 * сущность не наследует {@code AuditableEntity} (там {@code @GeneratedValue(IDENTITY)}),
 * аудит-колонки повторены явно.
 */
@NamedEntityGraphs({
        @NamedEntityGraph(
                name = "user-profile-graph",
                attributeNodes = @NamedAttributeNode("profile")
        )
})
@Getter
@Setter
@Builder
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email", columnList = "email")
})
public class User implements Serializable {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created", nullable = false, updatable = false)
    private LocalDateTime created;

    @UpdateTimestamp
    @Column(name = "updated", nullable = false)
    private LocalDateTime updated;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @Column(name = "last_modified_by")
    private String lastModifiedBy;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @OneToOne(
            fetch = FetchType.LAZY,
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @JoinColumn(
            name = "profile_id",
            foreignKey = @ForeignKey(name = "fk_user_profile")
    )
    private UserProfile profile;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    @Builder.Default
    private AccountStatus accountStatus = AccountStatus.PENDING;

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" +
                "id = " + id + ", " +
                "email = " + email + ", " +
                "accountStatus = " + accountStatus + ")";
    }
}
