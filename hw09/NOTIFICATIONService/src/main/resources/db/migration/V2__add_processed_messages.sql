-- Дедупликация входящих событий уведомлений: одна строка на event_id.
-- Уникальный PK защищает от конкурентной повторной доставки (redelivery) одного сообщения.
CREATE TABLE IF NOT EXISTS processed_messages
(
    event_id     VARCHAR(36) NOT NULL,
    processed_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_processed_messages PRIMARY KEY (event_id)
);
