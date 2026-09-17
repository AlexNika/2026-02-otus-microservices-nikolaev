-- Владелец брони: userId из JWT создателя заказа (передаётся ORDERService при резервировании).
-- Nullable: исторические брони до появления owner-поля.
ALTER TABLE delivery_reservations
    ADD COLUMN IF NOT EXISTS user_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_delivery_reservations_user_id ON delivery_reservations (user_id);
