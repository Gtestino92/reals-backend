CREATE TABLE matchmaking_availability_notification_episodes (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    next_check_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    handled_at     TIMESTAMP WITH TIME ZONE,

    PRIMARY KEY (id),

    CONSTRAINT fk_matchmaking_availability_notification_episodes_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_matchmaking_availability_notification_pending_user
    ON matchmaking_availability_notification_episodes (user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_matchmaking_availability_notification_due
    ON matchmaking_availability_notification_episodes (next_check_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_matchmaking_availability_notification_user
    ON matchmaking_availability_notification_episodes (user_id);
