-- orderId становится необязательным: уведомления о жизненном цикле регистрации
-- (USER_CREATED / USER_CREATION_FAILED / ACCOUNT_CREATED / ACCOUNT_CREATION_FAILED /
-- ACCOUNT_ACTIVATED) приходят без заказа (orderId=null).
ALTER TABLE notifications
    ALTER COLUMN order_id DROP NOT NULL;

-- notification_status хранит статус как есть (произвольная строка источника):
-- снимаем CHECK-ограничение SUCCESS/CANCELED/FAILED и расширяем колонку
-- (ACCOUNT_CREATION_FAILED - 23 символа, не влезает в VARCHAR(20)).
-- Существующие записи SUCCESS/FAILED не меняются; маппинг PLACED->SUCCESS сохраняется
-- на стороне маппера (совместимость со сценариями саги).
ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS notifications_notification_status_check;

ALTER TABLE notifications
    ALTER COLUMN notification_status TYPE VARCHAR(32);
