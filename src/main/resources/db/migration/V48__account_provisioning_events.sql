-- Independent of users: deletion/reactivation must not reset network quota.
CREATE TABLE account_provisioning_events (
    id UUID PRIMARY KEY,
    origin_hmac VARCHAR(64) NOT NULL CHECK (origin_hmac ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_provisioning_origin_time ON account_provisioning_events (origin_hmac, created_at, id);
CREATE INDEX idx_provisioning_expiry ON account_provisioning_events (created_at, id);
