package ru.otus.hw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.models.Account;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByUserId(Long userId);

    @Query("""
            SELECT a FROM Account a
            LEFT JOIN FETCH a.transactions t
            WHERE a.id = :id
            """)
    Optional<Account> findByIdWithTransactions(@Param("id") Long id);

    @Query("""
            SELECT a FROM Account a
            LEFT JOIN FETCH a.transactions t
            WHERE a.userId = :userId
            """)
    Optional<Account> findByUserIdWithTransactions(@Param("userId") Long userId);
    
}