ALTER TABLE profile_photos
    ADD COLUMN moderation_status VARCHAR(50);

UPDATE profile_photos
SET moderation_status = 'APPROVED'
WHERE moderation_status IS NULL;

ALTER TABLE profile_photos
    ALTER COLUMN moderation_status SET NOT NULL;
