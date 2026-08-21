CREATE INDEX idx_visual_reviews_created_match
    ON visual_reviews (created_at, match_id);
