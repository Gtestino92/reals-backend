ALTER TABLE profiles
    ADD COLUMN preferred_min_age INT,
    ADD COLUMN preferred_max_age INT,
    ADD COLUMN max_distance_km INT;

ALTER TABLE profiles
    ADD CONSTRAINT chk_profiles_preferred_min_age
        CHECK (preferred_min_age IS NULL OR preferred_min_age BETWEEN 18 AND 99),
    ADD CONSTRAINT chk_profiles_preferred_max_age
        CHECK (preferred_max_age IS NULL OR preferred_max_age BETWEEN 18 AND 99),
    ADD CONSTRAINT chk_profiles_preferred_age_range
        CHECK (
            preferred_min_age IS NULL
            OR preferred_max_age IS NULL
            OR preferred_min_age <= preferred_max_age
        ),
    ADD CONSTRAINT chk_profiles_max_distance_km
        CHECK (max_distance_km IS NULL OR max_distance_km BETWEEN 1 AND 1000);
