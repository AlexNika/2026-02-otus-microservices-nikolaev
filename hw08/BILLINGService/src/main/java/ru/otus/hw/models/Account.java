package ru.otus.hw.models;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.otus.hw.models.base.AuditableEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "accounts", indexes = {
        @Index(name = "idx_accounts_id_enabled", columnList = "id, enabled")
})
public class Account extends AuditableEntity<Long> {

    @Column(name = "user_id", unique = true)
    private Long userId;

    @Column(name = "balance", precision = 19, scale = 4)
    private BigDecimal balance;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private boolean locked = false;

    @Builder.Default
    @OneToMany(mappedBy = "account", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Transaction> transactions = new ArrayList<>();
}