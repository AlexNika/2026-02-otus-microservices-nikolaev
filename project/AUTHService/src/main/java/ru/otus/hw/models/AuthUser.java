package ru.otus.hw.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import ru.otus.hw.models.base.AuditableEntity;

import java.util.HashSet;
import java.util.Set;

/**
 * Учётные данные (credentials) пользователя - источник истины только в {@code auth_db}:
 * email, password_hash (BCrypt), роли. Профильные данные хранит USERService (тот же userId).
 */
@Getter
@Setter
@Builder
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "auth_users", indexes = {
        @Index(name = "idx_auth_users_email", columnList = "email")
})
public class AuthUser extends AuditableEntity<Long> {

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /**
     * Роли пользователя (USER/ADMIN) - связь ManyToMany.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(id = " + getId() + ", email = " + email + ")";
    }
}
