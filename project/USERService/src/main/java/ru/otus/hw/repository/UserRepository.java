package ru.otus.hw.repository;

import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.otus.hw.models.AccountStatus;
import ru.otus.hw.models.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    @NullMarked
    @EntityGraph("user-profile-graph")
    Optional<User> findById(Long id);

    /**
     * PENDING-пользователи старше тайм-аута активации (для PendingAccountTimeoutChecker).
     */
    List<User> findByAccountStatusAndCreatedBefore(AccountStatus accountStatus, LocalDateTime cutoff);
}
