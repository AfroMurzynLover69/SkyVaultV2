ALTER TABLE app_user
    ADD COLUMN theme VARCHAR(16) NOT NULL DEFAULT 'mono';

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_theme
        CHECK (theme IN ('mono', 'nord', 'forest', 'amber'));
