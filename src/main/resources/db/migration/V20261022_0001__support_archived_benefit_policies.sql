ALTER TABLE benefitcarrierpolicy
    DROP CONSTRAINT IF EXISTS ck_benefit_policy_publication_status;

ALTER TABLE benefitcarrierpolicy
    ADD CONSTRAINT ck_benefit_policy_publication_status
        CHECK (publicationstatus IN ('APPROVED', 'HELD', 'ARCHIVED'));

ALTER TABLE benefitcarrierpolicy
    DROP CONSTRAINT IF EXISTS ck_benefit_policy_held_reason;

ALTER TABLE benefitcarrierpolicy
    ADD CONSTRAINT ck_benefit_policy_held_reason
        CHECK (publicationstatus NOT IN ('HELD', 'ARCHIVED')
            OR (active = FALSE AND NULLIF(BTRIM(holdreason), '') IS NOT NULL));
