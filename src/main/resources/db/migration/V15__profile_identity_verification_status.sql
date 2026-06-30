ALTER TABLE profiles
    ADD COLUMN identity_verification_status VARCHAR(50);

UPDATE profiles
SET identity_verification_status = CASE
    WHEN identity_verified = true THEN 'VERIFIED'
    ELSE 'NOT_STARTED'
END;

ALTER TABLE profiles
    ALTER COLUMN identity_verification_status SET NOT NULL;
