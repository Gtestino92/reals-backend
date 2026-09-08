ALTER TABLE visual_reviews
    ADD COLUMN reminder_eligible_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_visual_reviews_reminder_candidates
    ON visual_reviews (reminder_eligible_at, expires_at);
