ALTER TABLE profile_photos
    ADD COLUMN IF NOT EXISTS validation_status VARCHAR(30) NOT NULL DEFAULT 'PENDING';

-- These columns are included here only if your current database does not already have them.
-- Remove any line that already exists in your schema/migrations.
ALTER TABLE profile_photos
    ADD COLUMN IF NOT EXISTS storage_provider VARCHAR(30) NOT NULL DEFAULT 'S3';

ALTER TABLE profile_photos
    ADD COLUMN IF NOT EXISTS storage_bucket VARCHAR(255);

ALTER TABLE profile_photos
    ADD COLUMN IF NOT EXISTS storage_key VARCHAR(500);
