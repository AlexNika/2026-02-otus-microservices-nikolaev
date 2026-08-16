-- Add product/delivery fields required by the order saga (warehouse + delivery reservations).
-- Defaults backfill legacy rows created before the saga was introduced; the JPA entity maps all
-- columns as NOT NULL and always writes them explicitly on insert.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS product_id    BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS quantity      INTEGER      NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS delivery_date DATE         NOT NULL DEFAULT CURRENT_DATE,
    ADD COLUMN IF NOT EXISTS slot_start    TIME         NOT NULL DEFAULT '10:00:00',
    ADD COLUMN IF NOT EXISTS slot_end      TIME         NOT NULL DEFAULT '12:00:00';
