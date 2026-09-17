INSERT INTO roles (version, created, updated, name, description)
VALUES (0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'USER', 'Default user role')
ON CONFLICT (name) DO NOTHING;

INSERT INTO roles (version, created, updated, name, description)
VALUES (0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'ADMIN', 'Administrator role with full access')
ON CONFLICT (name) DO NOTHING;
