ALTER TABLE chats
    ADD COLUMN conversation_started_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE second_chat_participations (
    id                 UUID         NOT NULL DEFAULT gen_random_uuid(),
    connection_id      UUID         NOT NULL,
    user_id            UUID         NOT NULL,
    attendance_status  VARCHAR(32)  NOT NULL,
    joined_at          TIMESTAMP WITH TIME ZONE,
    resolved_at        TIMESTAMP WITH TIME ZONE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    CONSTRAINT uq_second_chat_participation_connection_user
        UNIQUE (connection_id, user_id),

    CONSTRAINT fk_second_chat_participation_connection
        FOREIGN KEY (connection_id)
        REFERENCES connections(id),

    CONSTRAINT fk_second_chat_participation_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
);

CREATE INDEX idx_second_chat_participation_connection
    ON second_chat_participations (connection_id);

CREATE INDEX idx_second_chat_participation_user
    ON second_chat_participations (user_id);

CREATE INDEX idx_second_chat_participation_status
    ON second_chat_participations (attendance_status);

CREATE TABLE second_chat_resolution_requests (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    connection_id       UUID         NOT NULL,
    chat_id             UUID,
    requester_user_id   UUID         NOT NULL,
    responder_user_id   UUID         NOT NULL,
    type                VARCHAR(32)  NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMP WITH TIME ZONE,
    version             BIGINT       NOT NULL DEFAULT 0,

    PRIMARY KEY (id),

    CONSTRAINT fk_second_chat_resolution_connection
        FOREIGN KEY (connection_id)
        REFERENCES connections(id),

    CONSTRAINT fk_second_chat_resolution_chat
        FOREIGN KEY (chat_id)
        REFERENCES chats(id),

    CONSTRAINT fk_second_chat_resolution_requester
        FOREIGN KEY (requester_user_id)
        REFERENCES users(id),

    CONSTRAINT fk_second_chat_resolution_responder
        FOREIGN KEY (responder_user_id)
        REFERENCES users(id)
);

CREATE INDEX idx_second_chat_resolution_connection
    ON second_chat_resolution_requests (connection_id);

CREATE INDEX idx_second_chat_resolution_chat
    ON second_chat_resolution_requests (chat_id);

CREATE INDEX idx_second_chat_resolution_pending_expiry
    ON second_chat_resolution_requests (status, type, expires_at);

CREATE UNIQUE INDEX uq_second_chat_resolution_pending_partner_no_show
    ON second_chat_resolution_requests (connection_id)
    WHERE type = 'PARTNER_NO_SHOW'
      AND status = 'PENDING';
