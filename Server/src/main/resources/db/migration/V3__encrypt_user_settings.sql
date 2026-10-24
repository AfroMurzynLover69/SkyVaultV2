ALTER TABLE app_user
    ADD COLUMN encrypted_settings VARCHAR(8192),
    ADD COLUMN settings_iv VARCHAR(64);

ALTER TABLE app_user
    DROP COLUMN theme;
