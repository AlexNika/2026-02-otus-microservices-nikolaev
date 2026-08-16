package ru.otus.hw.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.otus.hw.models.Transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId")
    List<Transaction> findByAccountId(@Param("accountId") Long accountId);

    @Query("""
            SELECT t FROM Transaction t
            WHERE t.account.id = :accountId
              AND t.orderId = :orderId
            """)
    List<Transaction> findByAccountIdAndOrderId(
            @Param("accountId") Long accountId,
            @Param("orderId") Long orderId);

    @Query("""
            SELECT SUM(t.balanceAfter)
            FROM Transaction t
            WHERE t.account.id = :accountId
            """)
    BigDecimal sumBalanceAfterForAccount(@Param("accountId") Long accountId);

    @Query("""
            SELECT t FROM Transaction t
            WHERE t.account.id = :accountId
            ORDER BY t.updated DESC
            """)
    List<Transaction> findLatestTransactions(@Param("accountId") Long accountId, int limit);

    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdWithGraph(@Param("id") Long id);

    Optional<Transaction> findByOrderIdAndTransactionType(Long orderId, Transaction.TransactionType transactionType);

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}