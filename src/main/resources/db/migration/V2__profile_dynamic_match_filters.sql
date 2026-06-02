ALTER TABLE profiles
    ADD COLUMN preferred_min_age INT NOT NULL DEFAULT 18,
    ADD COLUMN preferred_max_age INT NOT NULL DEFAULT 99,
    ADD COLUMN max_distance_km INT NOT NULL DEFAULT 50;

ALTER TABLE profiles
    ALTER COLUMN preferred_min_age DROP DEFAULT,
    ALTER COLUMN preferred_max_age DROP DEFAULT,
    ALTER COLUMN max_distance_km DROP DEFAULT;

ALTER TABLE profiles
    ADD CONSTRAINT chk_profiles_preferred_min_age
        CHECK (preferred_min_age BETWEEN 18 AND 99),
    ADD CONSTRAINT chk_profiles_preferred_max_age
        CHECK (preferred_max_age BETWEEN 18 AND 99),
    ADD CONSTRAINT chk_profiles_preferred_age_range
        CHECK (preferred_min_age <= preferred_max_age),
    ADD CONSTRAINT chk_profiles_max_distance_km
        CHECK (max_distance_km BETWEEN 1 AND 1000);

ALTER TABLE matchmaking_queue
    ADD COLUMN latitude DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN longitude DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN accuracy_meters INT;

ALTER TABLE matchmaking_queue
    ALTER COLUMN latitude DROP DEFAULT,
    ALTER COLUMN longitude DROP DEFAULT;

ALTER TABLE matchmaking_queue
    ADD CONSTRAINT chk_matchmaking_queue_latitude
        CHECK (latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT chk_matchmaking_queue_longitude
        CHECK (longitude BETWEEN -180 AND 180),
    ADD CONSTRAINT chk_matchmaking_queue_accuracy_meters
        CHECK (accuracy_meters IS NULL OR accuracy_meters BETWEEN 0 AND 100000);
