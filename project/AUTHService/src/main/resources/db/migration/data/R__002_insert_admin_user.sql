INSERT INTO auth_users (version, created, updated, email, password_hash)
VALUES (0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
        'admin@admin.com', '$2a$12$cYqKqRIZR2h2MlRh7s7PfunQ8rPCPlULeJDD7AEr9giygKPVnXkDW')
ON CONFLICT (email) DO NOTHING;

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM auth_users u,
     roles r
WHERE u.email = 'admin@admin.com'
  AND r.name = 'ADMIN'
ON CONFLICT DO NOTHING;
