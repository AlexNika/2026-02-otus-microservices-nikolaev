-- Перенос authentication в AUTHService (чистый старт):
-- users остаётся проекцией идентифичности (id задаёт AuthService через UserCreatedEvent),
-- credentials/роли отсюда удаляются.
DROP TABLE IF EXISTS user_roles;
DROP TABLE IF EXISTS roles;

ALTER TABLE users DROP COLUMN IF EXISTS password;
ALTER TABLE users DROP COLUMN IF EXISTS enabled;
ALTER TABLE users DROP COLUMN IF EXISTS locked;
ALTER TABLE users DROP COLUMN IF EXISTS account_expired;
ALTER TABLE users DROP COLUMN IF EXISTS credentials_expired;

-- Индекс по (email, enabled) заменяется обычным индексом по email.
CREATE INDEX IF NOT EXISTS idx_users_email ON users (email);
