DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM profile_photos
        WHERE storage_key IS NULL OR btrim(storage_key) = ''
    ) THEN
        RAISE EXCEPTION 'Cannot drop profile_photos.url because some profile photos have no storage_key';
    END IF;
END $$;

ALTER TABLE profile_photos
    ALTER COLUMN storage_key SET NOT NULL;

ALTER TABLE profile_photos
    ALTER COLUMN storage_provider SET DEFAULT 'S3';

ALTER TABLE profile_photos
    DROP COLUMN url;
