ALTER TABLE chats
    ADD COLUMN ended_reason VARCHAR(64);

CREATE TABLE user_blocks (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    version         BIGINT       NOT NULL DEFAULT 0,
    blocker_user_id UUID         NOT NULL,
    blocked_user_id UUID         NOT NULL,
    source          VARCHAR(32)  NOT NULL,
    source_report_id UUID,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),

    PRIMARY KEY (id),

    CONSTRAINT uq_user_block_blocker_blocked
        UNIQUE (blocker_user_id, blocked_user_id),

    CONSTRAINT ck_user_block_not_self
        CHECK (blocker_user_id <> blocked_user_id),

    CONSTRAINT fk_user_block_blocker
        FOREIGN KEY (blocker_user_id)
        REFERENCES users(id),

    CONSTRAINT fk_user_block_blocked
        FOREIGN KEY (blocked_user_id)
        REFERENCES users(id),

    CONSTRAINT fk_user_block_source_report
        FOREIGN KEY (source_report_id)
        REFERENCES safety_reports(id)
);

CREATE INDEX idx_user_blocks_blocker
    ON user_blocks (blocker_user_id);

CREATE INDEX idx_user_blocks_blocked
    ON user_blocks (blocked_user_id);
