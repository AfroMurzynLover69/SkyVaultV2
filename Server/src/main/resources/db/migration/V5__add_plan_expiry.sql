ALTER TABLE app_user
    ADD COLUMN plan_started_at TIMESTAMPTZ,
    ADD COLUMN plan_expires_at TIMESTAMPTZ;

UPDATE app_user
SET plan_started_at = now(),
    plan_expires_at = now() + INTERVAL '30 days';

ALTER TABLE app_user
    ALTER COLUMN plan_started_at SET NOT NULL,
    ALTER COLUMN plan_expires_at SET NOT NULL;

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_plan_dates
        CHECK (plan_expires_at > plan_started_at);
