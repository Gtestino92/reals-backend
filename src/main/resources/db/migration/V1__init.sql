-- ============================================================
-- V1 - Initial schema for reals-backend
-- ============================================================

-- ShedLock table (required by ShedLock library)

CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- ============================================================
-- USERS
-- ============================================================

CREATE TABLE users (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    email       VARCHAR(255),
    firebase_uid VARCHAR(255),
    created_at  TIMESTAMP  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP  NOT NULL DEFAULT now(),

    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- ============================================================
-- PROFILES
-- ============================================================

CREATE TABLE profiles (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL,
    display_name        VARCHAR(100) NOT NULL,
    birth_date          DATE         NOT NULL,
    gender              VARCHAR(16)  NOT NULL,
    looking_for_gender  VARCHAR(16)  NOT NULL,
    intention           VARCHAR(16)  NOT NULL,
    city                VARCHAR(100) NOT NULL,
    country             VARCHAR(100) NOT NULL,
    bio                 TEXT,
    status              VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    created_at          TIMESTAMP  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP  NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_profiles_user
        UNIQUE (user_id),

    CONSTRAINT fk_profiles_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
);

-- ============================================================
-- PROFILE PHOTOS
-- ============================================================

CREATE TABLE profile_photos (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    profile_id      UUID         NOT NULL,
    url             VARCHAR(512) NOT NULL,
    position        INT          NOT NULL,
    is_person_photo BOOLEAN      NOT NULL DEFAULT false,
    is_full_body    BOOLEAN      NOT NULL DEFAULT false,
    created_at      TIMESTAMP  NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT fk_photos_profile
        FOREIGN KEY (profile_id)
        REFERENCES profiles(id)
);

CREATE INDEX idx_photos_profile
    ON profile_photos (profile_id);

-- ============================================================
-- ACTIVE ENGAGEMENT LOCKS
-- ============================================================

CREATE TABLE active_engagement_locks (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL,
    engagement_id    UUID         NOT NULL,
    engagement_type  VARCHAR(16)  NOT NULL,
    created_at       TIMESTAMP  NOT NULL DEFAULT now(),

    PRIMARY KEY (id)
);

CREATE INDEX idx_engagement_lock_user_id
    ON active_engagement_locks (user_id);

-- ============================================================
-- MATCHMAKING QUEUE
-- ============================================================

CREATE TABLE matchmaking_queue (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'WAITING',
    entered_at    TIMESTAMP  NOT NULL DEFAULT now(),
    processed_at  TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT uq_queue_user
        UNIQUE (user_id)
);

-- ============================================================
-- MATCHES
-- ============================================================

CREATE TABLE matches (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_a_id   UUID         NOT NULL,
    user_b_id   UUID         NOT NULL,
    state       VARCHAR(32)  NOT NULL DEFAULT 'CHAT_ACTIVE',
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),

    PRIMARY KEY (id)
);

CREATE INDEX idx_match_user_a
    ON matches (user_a_id);

CREATE INDEX idx_match_user_b
    ON matches (user_b_id);

CREATE INDEX idx_match_state
    ON matches (state);

-- ============================================================
-- CONNECTIONS
-- ============================================================

CREATE TABLE connections (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    match_id              UUID         NOT NULL,
    user_a_id             UUID         NOT NULL,
    user_b_id             UUID         NOT NULL,
    state                 VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULING_PHASE',
    scheduling_expires_at TIMESTAMP NOT NULL,
    created_at            TIMESTAMP NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_connection_match
        UNIQUE (match_id),

    CONSTRAINT fk_connection_match
        FOREIGN KEY (match_id)
        REFERENCES matches(id)
);

CREATE INDEX idx_connection_match
    ON connections (match_id);

CREATE INDEX idx_connection_state
    ON connections (state);

-- ============================================================
-- CHAT SESSIONS
-- ============================================================

CREATE TABLE chats (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    match_id         UUID         NOT NULL,
    connection_id    UUID,
    chat_type        VARCHAR(16)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    started_at       TIMESTAMP NOT NULL DEFAULT now(),
    timeout_at       TIMESTAMP NOT NULL,
    ended_at         TIMESTAMP,
    last_message_at  TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT fk_chat_match
        FOREIGN KEY (match_id)
        REFERENCES matches(id)
);

CREATE INDEX idx_chat_match
    ON chats (match_id);

-- ============================================================
-- CHAT MESSAGES
-- ============================================================

CREATE TABLE chat_messages (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    chat_session_id  UUID         NOT NULL,
    sender_id        UUID         NOT NULL,
    content          TEXT         NOT NULL,
    sent_at          TIMESTAMP NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT fk_message_chat
        FOREIGN KEY (chat_session_id)
        REFERENCES chats(id)
);

CREATE INDEX idx_message_session
    ON chat_messages (chat_session_id);

-- ============================================================
-- CHAT DECISIONS
-- ============================================================

CREATE TABLE chat_decisions (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    chat_id          UUID         NOT NULL,
    match_id         UUID         NOT NULL,
    user_a_decision  VARCHAR(16),
    user_b_decision  VARCHAR(16),
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_chat_decision_chat
        UNIQUE (chat_id),

    CONSTRAINT uq_chat_decision_match
        UNIQUE (match_id),

    CONSTRAINT fk_chat_decision_chat
        FOREIGN KEY (chat_id)
            REFERENCES chats(id),

    CONSTRAINT fk_chat_decision_match
        FOREIGN KEY (match_id)
            REFERENCES matches(id)
);

CREATE INDEX idx_chat_decision_match
    ON chat_decisions (match_id);

CREATE INDEX idx_chat_decision_chat
    ON chat_decisions (chat_id);

-- ============================================================
-- VISUAL REVIEWS
-- ============================================================

CREATE TABLE visual_reviews (
    id                     UUID         NOT NULL DEFAULT gen_random_uuid(),
    match_id               UUID         NOT NULL,
    user_a_visual_decision VARCHAR(16),
    user_b_visual_decision VARCHAR(16),
    personal_message_a     TEXT,
    personal_message_b     TEXT,
    messages_visible       BOOLEAN      NOT NULL DEFAULT false,
    expires_at             TIMESTAMP,
    created_at             TIMESTAMP NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_visual_review_match
        UNIQUE (match_id),

    CONSTRAINT fk_visual_review_match
        FOREIGN KEY (match_id)
        REFERENCES matches(id)
);

-- ============================================================
-- SCHEDULE NEGOTIATIONS
-- ============================================================

CREATE TABLE schedule_negotiations (
    id                    UUID         NOT NULL DEFAULT gen_random_uuid(),
    connection_id         UUID         NOT NULL,
    round_number          INT          NOT NULL DEFAULT 1,
    status                VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    confirmed_date_time   TIMESTAMP,
    created_at            TIMESTAMP NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_negotiation_connection
        UNIQUE (connection_id),

    CONSTRAINT fk_negotiation_connection
        FOREIGN KEY (connection_id)
        REFERENCES connections(id)
);

-- ============================================================
-- SCHEDULE PROPOSALS
-- ============================================================

CREATE TABLE schedule_proposals (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    connection_id       UUID         NOT NULL,
    user_id             UUID         NOT NULL,
    proposed_date_time  TIMESTAMP NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMP NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT fk_proposal_connection
        FOREIGN KEY (connection_id)
        REFERENCES connections(id)
);

CREATE INDEX idx_proposal_connection
    ON schedule_proposals (connection_id);

-- ============================================================
-- PENALTIES
-- ============================================================

CREATE TABLE penalties (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    reason      VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,

    PRIMARY KEY (id)
);

CREATE INDEX idx_penalty_user
    ON penalties (user_id);
