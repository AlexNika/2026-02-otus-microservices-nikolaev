package ru.otus.hw.models;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.NamedAttributeNode;
import jakarta.persistence.NamedEntityGraph;
import jakarta.persistence.NamedEntityGraphs;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import ru.otus.hw.models.base.AuditableEntity;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@NamedEntityGraphs({
        @NamedEntityGraph(
                name = "user-role-graph",
                attributeNodes = @NamedAttributeNode("roles")
        ),
        @NamedEntityGraph(
                name = "user-profile-graph",
                attributeNodes = @NamedAttributeNode("profile")
        )
})
@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email_enabled", columnList = "email, enabled")
})
public class User extends AuditableEntity<Long> implements UserDetails {

    static final String PASSWORD_REGEXP_PATTERN =
            "^(?=.*[0-9])(?=.*[a-z])(?=.*[A-Z])(?=.*[!@#$%^&+=])(?=\\S+$).{8,64}$";

    @Email(message = "Email: '${validatedValue}' should be valid")
    @NotBlank(message = "{validation.email.notEmpty}")
    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Size(min=8, message="Minimum {min} characters required for password: '${validatedValue}'")
    @Pattern(regexp = PASSWORD_REGEXP_PATTERN,
            message ="Invalid format of the entered data.")
    @NotBlank(message = "Password cannot be null or empty")
    @Column(name = "password", nullable = false)
    private String password;

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

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Fetch(FetchMode.SUBSELECT)
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private boolean locked = false;

    @Builder.Default
    private boolean accountExpired = false;

    @Builder.Default
    private boolean credentialsExpired = false;

    @Override
    @NullMarked
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (roles == null) return Set.of();
        return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                .collect(Collectors.toSet());
    }

    @Override
    public @Nullable String getPassword() {
        return password;
    }

    @Override
    @NullMarked
    public String getUsername() {
        return email;
    }

    @NullMarked
    public String getDisplayName() {
        return profile != null && profile.getUserName() != null ? profile.getUserName() : email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return !accountExpired;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return !credentialsExpired;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" +
                "id = " + getId() + ", " +
                "email = " + getEmail() + ", " +
                "enabled = " + isEnabled() + ", " +
                "locked = " + isLocked() + ", " +
                "accountExpired = " + isAccountExpired() + ", " +
                "credentialsExpired = " + isCredentialsNonExpired() + ")";
    }
}