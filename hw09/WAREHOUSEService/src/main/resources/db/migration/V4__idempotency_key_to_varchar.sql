ALTER TABLE product_reservations
    ALTER COLUMN idempotency_key TYPE VARCHAR(64) USING idempotency_key::text;
