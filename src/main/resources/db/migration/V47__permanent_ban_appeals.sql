DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM penalties
        WHERE active = true
          AND type = 'PERMANENT_BAN'
        GROUP BY user_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Cannot add active permanent-ban uniqueness while duplicate active permanent bans exist';
    END IF;
END $$;

ALTER TABLE penalties
    ADD COLUMN appeal_status VARCHAR(32),
    ADD COLUMN appeal_statement TEXT,
    ADD COLUMN appealed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN appeal_reviewed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN appeal_reviewed_by_user_id UUID,
    ADD COLUMN appeal_review_notes TEXT;

ALTER TABLE penalties
    ADD CONSTRAINT chk_penalties_appeal_status
        CHECK (
            appeal_status IS NULL
            OR appeal_status IN ('PENDING', 'APPROVED', 'REJECTED')
        ),
    ADD CONSTRAINT chk_penalties_appeal_only_permanent
        CHECK (
            (
                type = 'PERMANENT_BAN'
            )
            OR (
                appeal_status IS NULL
                AND appeal_statement IS NULL
                AND appealed_at IS NULL
                AND appeal_reviewed_at IS NULL
                AND appeal_reviewed_by_user_id IS NULL
                AND appeal_review_notes IS NULL
            )
        ),
    ADD CONSTRAINT chk_penalties_appeal_shape
        CHECK (
            (
                appeal_status IS NULL
                AND appeal_statement IS NULL
                AND appealed_at IS NULL
                AND appeal_reviewed_at IS NULL
                AND appeal_reviewed_by_user_id IS NULL
                AND appeal_review_notes IS NULL
            )
            OR (
                appeal_status = 'PENDING'
                AND appeal_statement IS NOT NULL
                AND btrim(appeal_statement) <> ''
                AND appealed_at IS NOT NULL
                AND appeal_reviewed_at IS NULL
                AND appeal_reviewed_by_user_id IS NULL
                AND appeal_review_notes IS NULL
            )
            OR (
                appeal_status IN ('APPROVED', 'REJECTED')
                AND appeal_statement IS NOT NULL
                AND btrim(appeal_statement) <> ''
                AND appealed_at IS NOT NULL
                AND appeal_reviewed_at IS NOT NULL
                AND appeal_reviewed_by_user_id IS NOT NULL
                AND appeal_review_notes IS NOT NULL
                AND btrim(appeal_review_notes) <> ''
            )
        );

CREATE INDEX idx_penalties_pending_appeals_appealed_at
    ON penalties (appealed_at)
    WHERE appeal_status = 'PENDING';

CREATE UNIQUE INDEX uq_penalties_one_active_permanent_ban_per_user
    ON penalties (user_id)
    WHERE active = true
      AND type = 'PERMANENT_BAN';
