-- Idempotency guard for saga operations: at most one transaction per (order_id, transaction_type).
-- Partial index keeps DEPOSIT transactions without order_id unaffected.
CREATE UNIQUE INDEX uq_transactions_order_id_type
    ON transactions (order_id, transaction_type)
    WHERE order_id IS NOT NULL;
