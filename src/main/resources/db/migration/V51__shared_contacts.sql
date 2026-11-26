CREATE TABLE IF NOT EXISTS contact_participation (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    consent_version integer NOT NULL,
    accepted_at timestamp with time zone NOT NULL,
    key_version integer,
    synced_at timestamp with time zone,
    generation uuid NOT NULL,
    CHECK ((key_version IS NULL) = (synced_at IS NULL))
);
CREATE TABLE IF NOT EXISTS contact_identifiers (
    user_id uuid NOT NULL REFERENCES contact_participation(user_id) ON DELETE CASCADE,
    digest bytea NOT NULL CHECK (octet_length(digest) = 32),
    PRIMARY KEY (user_id, digest)
);
CREATE TABLE IF NOT EXISTS contact_replacements (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    replaced_at timestamp with time zone NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_contact_replacement_owner ON contact_replacements(user_id, replaced_at);
CREATE TABLE IF NOT EXISTS match_contact_context (
    match_id uuid PRIMARY KEY REFERENCES matches(id) ON DELETE CASCADE,
    contact_range varchar(20) NOT NULL CHECK (contact_range IN ('ONE_TO_FIVE','SIX_TO_TEN','MORE_THAN_TEN')),
    generation_a uuid NOT NULL,
    generation_b uuid NOT NULL
);
