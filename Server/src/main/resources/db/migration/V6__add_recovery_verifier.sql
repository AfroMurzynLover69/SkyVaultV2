ALTER TABLE app_user
    ADD COLUMN recovery_verifier_hash VARCHAR(64);

COMMENT ON COLUMN app_user.recovery_verifier_hash IS
    'SHA-256 of the recovery verifier derived from the master key; NULL for accounts created before recovery support.';
