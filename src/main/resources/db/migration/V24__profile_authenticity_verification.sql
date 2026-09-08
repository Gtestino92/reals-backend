ALTER TABLE profiles
    RENAME COLUMN identity_verified TO authenticity_verified;

ALTER TABLE profiles
    RENAME COLUMN identity_verification_status TO authenticity_verification_status;

UPDATE audit_events
SET event_type = 'PROFILE_AUTHENTICITY_VERIFICATION_UPDATED'
WHERE event_type = 'IDENTITY_VERIFICATION_UPDATED';
