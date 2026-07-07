CREATE TABLE profile_looking_for_genders (
    profile_id UUID        NOT NULL,
    gender     VARCHAR(16) NOT NULL,

    PRIMARY KEY (profile_id, gender),

    CONSTRAINT fk_profile_looking_for_genders_profile
        FOREIGN KEY (profile_id)
        REFERENCES profiles(id)
);

INSERT INTO profile_looking_for_genders (profile_id, gender)
SELECT p.id, mapped.gender
FROM profiles p
CROSS JOIN LATERAL (
    SELECT 'MALE' AS gender
    WHERE p.looking_for_gender IN ('MEN', 'EVERYONE')

    UNION ALL

    SELECT 'FEMALE' AS gender
    WHERE p.looking_for_gender IN ('WOMEN', 'EVERYONE')

    UNION ALL

    SELECT 'NON_BINARY' AS gender
    WHERE p.looking_for_gender IN ('OTHER', 'EVERYONE')

    UNION ALL

    SELECT 'OTHER' AS gender
    WHERE p.looking_for_gender IN ('OTHER', 'EVERYONE')
) mapped;

DROP INDEX IF EXISTS idx_profiles_matchmaking_basic;

CREATE INDEX idx_profiles_matchmaking_basic
    ON profiles (status, intention, gender, user_id);

CREATE INDEX idx_profile_looking_for_genders_gender_profile
    ON profile_looking_for_genders (gender, profile_id);

ALTER TABLE profiles
    DROP COLUMN looking_for_gender;
