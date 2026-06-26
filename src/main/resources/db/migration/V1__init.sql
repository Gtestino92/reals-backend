-- ============================================================
-- V1 - Initial schema for reals-backend
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

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
    version     BIGINT       NOT NULL DEFAULT 0,
    email       VARCHAR(255),
    firebase_uid VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_firebase_uid UNIQUE (firebase_uid)
);

-- ============================================================
-- PROFILES
-- ============================================================

CREATE TABLE profiles (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    version             BIGINT       NOT NULL DEFAULT 0,
    user_id             UUID         NOT NULL,
    display_name        VARCHAR(100) NOT NULL,
    birth_date          DATE         NOT NULL,
    identity_verified   BOOLEAN      NOT NULL DEFAULT false,
    gender              VARCHAR(16)  NOT NULL,
    looking_for_gender  VARCHAR(16)  NOT NULL,
    intention           VARCHAR(16)  NOT NULL,
    city                VARCHAR(100) NOT NULL,
    country             VARCHAR(100) NOT NULL,
    bio                 TEXT,
    status              VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_profiles_user
        UNIQUE (user_id),

    CONSTRAINT fk_profiles_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
);

CREATE INDEX idx_profiles_matchmaking_basic
    ON profiles (status, intention, gender, looking_for_gender, user_id);

-- ============================================================
-- PROFILE PHOTOS
-- ============================================================

CREATE TABLE profile_photos (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    version         BIGINT       NOT NULL DEFAULT 0,
    profile_id      UUID         NOT NULL,
    url             VARCHAR(512) NOT NULL,
    storage_provider VARCHAR(32)  NOT NULL DEFAULT 'S3',
    storage_bucket   VARCHAR(255),
    storage_key      VARCHAR(1024),
    position        INT          NOT NULL,
    is_person_photo BOOLEAN      NOT NULL DEFAULT false,
    is_full_body    BOOLEAN      NOT NULL DEFAULT false,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_profile_photo_profile_position
        UNIQUE (profile_id, position),

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
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_engagement_lock_user_engagement
        UNIQUE (user_id, engagement_id)
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
    entered_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    processed_at  TIMESTAMP WITH TIME ZONE,

    PRIMARY KEY (id),

    CONSTRAINT uq_queue_user
        UNIQUE (user_id)
);

CREATE INDEX idx_matchmaking_queue_waiting
    ON matchmaking_queue (status, entered_at, id);

-- ============================================================
-- MATCHES
-- ============================================================

CREATE TABLE matches (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    version     BIGINT       NOT NULL DEFAULT 0,
    user_a_id   UUID         NOT NULL,
    user_b_id   UUID         NOT NULL,
    state       VARCHAR(32)  NOT NULL DEFAULT 'CHAT_ACTIVE',
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

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
    version               BIGINT       NOT NULL DEFAULT 0,
    match_id              UUID         NOT NULL,
    user_a_id             UUID         NOT NULL,
    user_b_id             UUID         NOT NULL,
    state                 VARCHAR(32)  NOT NULL DEFAULT 'SCHEDULING_PHASE',
    scheduling_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

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
    version          BIGINT       NOT NULL DEFAULT 0,
    match_id         UUID         NOT NULL,
    connection_id    UUID,
    chat_type        VARCHAR(16)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    available_at     TIMESTAMP WITH TIME ZONE,
    activated_at     TIMESTAMP WITH TIME ZONE,
    timeout_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    ended_at         TIMESTAMP WITH TIME ZONE,
    last_message_at  TIMESTAMP WITH TIME ZONE,

    PRIMARY KEY (id),

    CONSTRAINT fk_chat_match
        FOREIGN KEY (match_id)
        REFERENCES matches(id),

    CONSTRAINT uq_chat_match_type
        UNIQUE (match_id, chat_type),

    CONSTRAINT uq_chat_connection_type
        UNIQUE (connection_id, chat_type)
);

CREATE INDEX idx_chat_match
    ON chats (match_id);

-- ============================================================
-- CHAT EXIT REQUESTS
-- ============================================================

CREATE TABLE chat_exit_requests (
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    version            BIGINT       NOT NULL DEFAULT 0,
    chat_id            UUID         NOT NULL,
    requester_user_id  UUID         NOT NULL,
    responder_user_id  UUID         NOT NULL,
    request_type       VARCHAR(32)  NOT NULL,
    status             VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    reason             VARCHAR(64),
    details            TEXT,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMP WITH TIME ZONE,

    PRIMARY KEY (id),

    CONSTRAINT fk_chat_exit_request_chat
        FOREIGN KEY (chat_id)
        REFERENCES chats(id)
);

CREATE INDEX idx_chat_exit_request_chat
    ON chat_exit_requests (chat_id);

-- ============================================================
-- CHAT MESSAGES
-- ============================================================

CREATE TABLE chat_messages (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    chat_session_id  UUID         NOT NULL,
    sender_id        UUID         NOT NULL,
    content          TEXT         NOT NULL,
    sent_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

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
    version          BIGINT       NOT NULL DEFAULT 0,
    chat_id          UUID         NOT NULL,
    match_id         UUID         NOT NULL,
    user_a_decision  VARCHAR(16),
    user_b_decision  VARCHAR(16),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

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
    version                BIGINT       NOT NULL DEFAULT 0,
    match_id               UUID         NOT NULL,
    user_a_visual_decision VARCHAR(16),
    user_b_visual_decision VARCHAR(16),
    personal_message_a     TEXT,
    personal_message_b     TEXT,
    personal_message_a_read_by_b_at TIMESTAMP WITH TIME ZONE,
    personal_message_b_read_by_a_at TIMESTAMP WITH TIME ZONE,
    messages_visible       BOOLEAN      NOT NULL DEFAULT false,
    expires_at             TIMESTAMP WITH TIME ZONE,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

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
    version               BIGINT       NOT NULL DEFAULT 0,
    connection_id         UUID         NOT NULL,
    round_number          INT          NOT NULL DEFAULT 1,
    status                VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    confirmed_date_time   TIMESTAMP WITH TIME ZONE,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

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
    version             BIGINT       NOT NULL DEFAULT 0,
    connection_id       UUID         NOT NULL,
    user_id             UUID         NOT NULL,
    round_number        INT          NOT NULL DEFAULT 1,
    preference_order    INT          NOT NULL DEFAULT 1,
    proposed_date_time  TIMESTAMP WITH TIME ZONE NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_schedule_proposal_user_round_order
        UNIQUE (connection_id, user_id, round_number, preference_order),

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
    version     BIGINT       NOT NULL DEFAULT 0,
    user_id     UUID         NOT NULL,
    reason      VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,

    PRIMARY KEY (id)
);

CREATE INDEX idx_penalty_user
    ON penalties (user_id);
