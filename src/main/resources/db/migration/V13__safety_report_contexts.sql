ALTER TABLE safety_reports
    ADD COLUMN context_type VARCHAR(32),
    ADD COLUMN context_id UUID;

UPDATE safety_reports
SET context_type = 'CHAT',
    context_id = chat_id
WHERE context_type IS NULL;

ALTER TABLE safety_reports
    ALTER COLUMN context_type SET NOT NULL,
    ALTER COLUMN chat_id DROP NOT NULL;

CREATE UNIQUE INDEX uq_safety_report_reporter_reported_context
    ON safety_reports (reporter_user_id, reported_user_id, context_type, context_id);
