CREATE TABLE user_reliability_events (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid(),
    version                  BIGINT       NOT NULL DEFAULT 0,
    user_id                  UUID         NOT NULL,
    related_match_id          UUID,
    related_connection_id     UUID,
    related_chat_id           UUID,
    related_safety_report_id  UUID,
    event_type                VARCHAR(96)  NOT NULL,
    dimension                 VARCHAR(64)  NOT NULL,
    delta                     INTEGER      NOT NULL,
    occurred_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    metadata                  JSONB,

    PRIMARY KEY (id),

    CONSTRAINT fk_user_reliability_events_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_user_reliability_events_user_expires
    ON user_reliability_events (user_id, expires_at);

CREATE INDEX idx_user_reliability_events_expires
    ON user_reliability_events (expires_at);

CREATE UNIQUE INDEX uq_user_reliability_events_match
    ON user_reliability_events (user_id, event_type, related_match_id)
    WHERE related_match_id IS NOT NULL;

CREATE UNIQUE INDEX uq_user_reliability_events_connection
    ON user_reliability_events (user_id, event_type, related_connection_id)
    WHERE related_connection_id IS NOT NULL;

CREATE UNIQUE INDEX uq_user_reliability_events_chat
    ON user_reliability_events (user_id, event_type, related_chat_id)
    WHERE related_chat_id IS NOT NULL;

CREATE UNIQUE INDEX uq_user_reliability_events_safety_report
    ON user_reliability_events (user_id, event_type, related_safety_report_id)
    WHERE related_safety_report_id IS NOT NULL;
