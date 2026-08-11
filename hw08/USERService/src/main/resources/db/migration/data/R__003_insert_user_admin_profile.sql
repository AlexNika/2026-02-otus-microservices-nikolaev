-- Insert admin user profile (only if not exists)
INSERT INTO user_profile (id, version, created, updated, created_by, last_modified_by, username, firstname, lastname, birthdate)
SELECT u.id, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'system', 'system', 'admin', 'Administrator', 'System', NULL
FROM users u
WHERE u.email = 'admin@admin.com'
AND NOT EXISTS (
    SELECT 1 FROM user_profile up WHERE up.id = u.id
);

-- Update admin user to link to the profile (only if not already linked)
UPDATE users
SET profile_id = (SELECT id FROM user_profile WHERE username = 'admin')
WHERE email = 'admin@admin.com'
AND profile_id IS NULL;
