CREATE TABLE admin_mfa_state (
    user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    factor_id VARCHAR(64) NOT NULL,
    last_accepted_step BIGINT NOT NULL DEFAULT -1 CHECK (last_accepted_step >= -1),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    failure_window_started_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE admin_access_grants (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    firebase_uid VARCHAR(128) NOT NULL,
    auth_time BIGINT NOT NULL CHECK (auth_time >= 0),
    provider VARCHAR(16) NOT NULL CHECK (provider = 'GOOGLE'),
    web_app_id VARCHAR(255) NOT NULL,
    factor_id VARCHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at > issued_at),
    revoked_at TIMESTAMPTZ
);
CREATE INDEX idx_admin_grants_expiration ON admin_access_grants(expires_at, token_hash);
CREATE INDEX idx_admin_grants_user ON admin_access_grants(user_id);
