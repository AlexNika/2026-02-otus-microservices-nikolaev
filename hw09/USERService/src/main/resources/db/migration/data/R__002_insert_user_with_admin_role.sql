-- Insert admin user with default password 'admin' (BCrypt encoded, round=12)
-- Using ON CONFLICT to make migration idempotent
INSERT INTO users (version, created, updated, created_by, last_modified_by, email, password, enabled, locked, account_expired, credentials_expired)
VALUES (1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system', 'admin@admin.com', '$2a$12$cYqKqRIZR2h2MlRh7s7PfunQ8rPCPlULeJDD7AEr9giygKPVnXkDW', TRUE, FALSE, FALSE, FALSE)
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE u.email = 'admin@admin.com' AND r.name = 'ADMIN'
AND NOT EXISTS (
    SELECT 1 FROM user_roles ur 
    WHERE ur.user_id = u.id AND ur.role_id = r.id
);
