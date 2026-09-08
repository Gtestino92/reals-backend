UPDATE profiles
SET authenticity_verified = CASE
    WHEN authenticity_verification_status = 'VERIFIED' THEN TRUE
    ELSE FALSE
END;

ALTER TABLE profiles
    ADD CONSTRAINT chk_profiles_authenticity_verified_status_consistent
        CHECK (authenticity_verified = (authenticity_verification_status = 'VERIFIED'));
