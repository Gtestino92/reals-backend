CREATE TABLE safety_reports (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    version             BIGINT       NOT NULL DEFAULT 0,
    reporter_user_id    UUID         NOT NULL,
    reported_user_id    UUID         NOT NULL,
    chat_id             UUID         NOT NULL,
    match_id            UUID         NOT NULL,
    connection_id       UUID,
    reason              VARCHAR(64)  NOT NULL,
    details             TEXT         NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    reviewed_at         TIMESTAMP WITH TIME ZONE,
    reviewed_by_user_id UUID,
    verdict_notes       TEXT,
    penalty_id          UUID,

    PRIMARY KEY (id),

    CONSTRAINT fk_safety_report_chat
        FOREIGN KEY (chat_id)
        REFERENCES chats(id)
);

CREATE INDEX idx_safety_reports_status_created_at
    ON safety_reports (status, created_at);

CREATE INDEX idx_safety_reports_reported_user
    ON safety_reports (reported_user_id);

CREATE INDEX idx_safety_reports_reporter_user
    ON safety_reports (reporter_user_id);

ALTER TABLE penalties
    ALTER COLUMN expires_at DROP NOT NULL;

ALTER TABLE penalties
    ADD COLUMN type VARCHAR(32) NOT NULL DEFAULT 'TEMPORARY_BAN',
    ADD COLUMN source_report_id UUID,
    ADD COLUMN applied_by_user_id UUID;

CREATE INDEX idx_penalties_active_user
    ON penalties (active, user_id);

CREATE INDEX idx_penalties_source_report
    ON penalties (source_report_id);
