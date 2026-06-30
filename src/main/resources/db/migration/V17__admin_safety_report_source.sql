ALTER TABLE safety_reports
    ALTER COLUMN reporter_user_id DROP NOT NULL;

ALTER TABLE safety_reports
    ALTER COLUMN match_id DROP NOT NULL;

ALTER TABLE safety_reports
    ADD COLUMN source VARCHAR(50);

UPDATE safety_reports
SET source = 'USER'
WHERE source IS NULL;

ALTER TABLE safety_reports
    ALTER COLUMN source SET NOT NULL;

ALTER TABLE safety_reports
    ADD COLUMN created_by_admin_user_id UUID;

CREATE INDEX idx_safety_reports_reported_user_id ON safety_reports (reported_user_id);
CREATE INDEX idx_safety_reports_reporter_user_id ON safety_reports (reporter_user_id);
CREATE INDEX idx_safety_reports_source ON safety_reports (source);
CREATE INDEX idx_safety_reports_created_by_admin_user_id ON safety_reports (created_by_admin_user_id);
CREATE INDEX idx_safety_reports_status ON safety_reports (status);
CREATE INDEX idx_safety_reports_created_at ON safety_reports (created_at);
