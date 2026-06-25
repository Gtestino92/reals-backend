CREATE TABLE connection_home_dismissals (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL,
    connection_id UUID         NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_connection_home_dismissal_user_connection
        UNIQUE (user_id, connection_id),

    CONSTRAINT fk_connection_home_dismissal_connection
        FOREIGN KEY (connection_id)
        REFERENCES connections(id)
);

CREATE INDEX idx_connection_home_dismissal_user
    ON connection_home_dismissals (user_id);

CREATE INDEX idx_connection_home_dismissal_connection
    ON connection_home_dismissals (connection_id);
