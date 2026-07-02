CREATE TABLE push_device_tokens (
    id           UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id      UUID         NOT NULL,
    token        TEXT         NOT NULL,
    platform     VARCHAR(32)  NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_push_device_tokens_token
        UNIQUE (token),

    CONSTRAINT fk_push_device_tokens_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
);

CREATE INDEX idx_push_device_tokens_user_id
    ON push_device_tokens (user_id);

CREATE TABLE push_notification_deliveries (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id              UUID         NOT NULL,
    notification_type    VARCHAR(64)  NOT NULL,
    aggregate_id         UUID         NOT NULL,
    sent_at              TIMESTAMP WITH TIME ZONE,
    status               VARCHAR(32)  NOT NULL,
    provider_message_id  TEXT,
    error_message        TEXT,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_push_notification_delivery
        UNIQUE (user_id, notification_type, aggregate_id),

    CONSTRAINT fk_push_notification_deliveries_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
);
