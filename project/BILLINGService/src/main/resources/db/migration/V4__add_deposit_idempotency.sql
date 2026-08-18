-- Идемпотентность публичного deposit: опциональный idempotency_key на транзакции.
-- Частичный уникальный индекс гарантирует не более одной транзакции на заданный ключ;
-- строки без ключа (старые запросы) индекс не затрагивает.
ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_idempotency_key
    ON transactions (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
