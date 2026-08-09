ALTER TABLE visual_reviews
    ADD COLUMN available_at TIMESTAMP WITH TIME ZONE;

UPDATE visual_reviews
SET available_at = created_at
WHERE available_at IS NULL;

ALTER TABLE visual_reviews
    ALTER COLUMN available_at SET NOT NULL;

ALTER TABLE user_home_status
    ADD COLUMN next_refresh_at TIMESTAMP WITH TIME ZONE;
