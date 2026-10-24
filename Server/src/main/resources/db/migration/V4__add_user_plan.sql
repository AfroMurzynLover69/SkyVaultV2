ALTER TABLE app_user
    ADD COLUMN plan VARCHAR(16) NOT NULL DEFAULT 'free';

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_plan
        CHECK (plan IN ('free', 'plus', 'pro'));
