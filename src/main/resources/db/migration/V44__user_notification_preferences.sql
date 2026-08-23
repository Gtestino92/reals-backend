CREATE TABLE user_notification_preferences (
    id          UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    category    VARCHAR(32)  NOT NULL,
    enabled     BOOLEAN      NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_user_notification_preferences_user_category
        UNIQUE (user_id, category),

    CONSTRAINT fk_user_notification_preferences_user
        FOREIGN KEY (user_id)
        REFERENCES users(id)
);

CREATE INDEX idx_user_notification_preferences_user_id
    ON user_notification_preferences (user_id);
