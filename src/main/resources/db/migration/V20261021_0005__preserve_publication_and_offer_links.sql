ALTER TABLE benefitcarrierpolicy
    ADD COLUMN IF NOT EXISTS publicationstatus VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN IF NOT EXISTS holdreason VARCHAR(512),
    ADD COLUMN IF NOT EXISTS publicationreviewedat TIMESTAMPTZ;

UPDATE benefitcarrierpolicy SET publicationstatus = 'APPROVED' WHERE publicationstatus IS NULL;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_benefit_policy_publication_status') THEN
        ALTER TABLE benefitcarrierpolicy ADD CONSTRAINT ck_benefit_policy_publication_status
            CHECK (publicationstatus IN ('APPROVED', 'HELD'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_benefit_policy_held_reason') THEN
        ALTER TABLE benefitcarrierpolicy ADD CONSTRAINT ck_benefit_policy_held_reason
            CHECK (publicationstatus <> 'HELD' OR (active = FALSE AND NULLIF(BTRIM(holdreason), '') IS NOT NULL));
    END IF;
END $$;

ALTER TABLE carriertierbenefit ADD COLUMN IF NOT EXISTS offerkey VARCHAR(512);
