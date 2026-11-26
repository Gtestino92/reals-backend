ALTER TABLE admin_access_grants
    ADD COLUMN scope VARCHAR(16) NOT NULL DEFAULT 'BACKOFFICE',
    DROP CONSTRAINT admin_access_grants_provider_check,
    ADD CONSTRAINT admin_grant_provider_scope CHECK (
        (provider = 'GOOGLE' AND scope = 'BACKOFFICE') OR
        (provider = 'PASSWORD' AND scope = 'DEV_TOOLING')
    );
