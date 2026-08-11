ALTER TABLE users
ADD COLUMN auth_origin VARCHAR(32) NULL;

UPDATE users
SET auth_origin = 'EMAIL_PASSWORD'
WHERE firebase_uid IS NOT NULL
  AND auth_origin IS NULL;

ALTER TABLE users
ADD CONSTRAINT ck_users_auth_origin
CHECK (
    auth_origin IS NULL OR
    auth_origin = 'EMAIL_PASSWORD' OR
    auth_origin = 'GOOGLE'
);
