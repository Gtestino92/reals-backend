DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM profiles
        WHERE country IS NULL
           OR btrim(country) !~ '^[A-Za-z]{2}$'
    ) THEN
        RAISE EXCEPTION 'profiles.country contains legacy non-alpha-2 country values; normalize them before applying the country_code migration';
    END IF;
END $$;

UPDATE profiles
SET country = upper(btrim(country));

ALTER TABLE profiles
    RENAME COLUMN country TO country_code;

ALTER TABLE profiles
    ALTER COLUMN country_code TYPE VARCHAR(2);

ALTER TABLE profiles
    ALTER COLUMN country_code SET NOT NULL;

ALTER TABLE profiles
    ADD CONSTRAINT chk_profiles_country_code_format
        CHECK (country_code ~ '^[A-Z]{2}$');
