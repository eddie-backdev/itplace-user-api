CREATE TABLE IF NOT EXISTS rawBenefitSource (
    id BIGSERIAL PRIMARY KEY,
    carrier VARCHAR(10) NOT NULL,
    sourceUrl VARCHAR(512) NOT NULL,
    sourceKey VARCHAR(512),
    contentType VARCHAR(100),
    sha256 VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    capturedAt TIMESTAMPTZ NOT NULL,
    lastSeenAt TIMESTAMPTZ,
    CONSTRAINT uq_raw_benefit_source_content UNIQUE (carrier, sourceUrl, sha256)
);
ALTER TABLE rawBenefitSource ADD COLUMN IF NOT EXISTS lastSeenAt TIMESTAMPTZ;
UPDATE rawBenefitSource SET lastSeenAt = capturedAt WHERE lastSeenAt IS NULL;
ALTER TABLE rawBenefitSource ALTER COLUMN lastSeenAt SET NOT NULL;
ALTER TABLE benefitCarrierPolicy
    ADD COLUMN IF NOT EXISTS sourceEvidenceId BIGINT,
    ADD COLUMN IF NOT EXISTS sourceCapturedAt TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS sourceLimitText TEXT,
    ADD COLUMN IF NOT EXISTS conditions JSONB;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM benefitCarrierPolicy policy
        LEFT JOIN rawBenefitSource source ON source.id = policy.sourceEvidenceId
        WHERE policy.sourceEvidenceId IS NOT NULL AND source.id IS NULL
    ) THEN
        RAISE EXCEPTION 'benefitCarrierPolicy contains sourceEvidenceId values without rawBenefitSource rows';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid = 'benefitcarrierpolicy'::regclass
          AND constraint_row.confrelid = 'rawbenefitsource'::regclass
          AND constraint_row.contype = 'f'
          AND EXISTS (
              SELECT 1 FROM unnest(constraint_row.conkey) AS column_number(attnum)
              JOIN pg_attribute column_row ON column_row.attrelid = constraint_row.conrelid
               AND column_row.attnum = column_number.attnum
              WHERE column_row.attname = 'sourceevidenceid'
          )
    ) THEN
        ALTER TABLE benefitCarrierPolicy
            ADD CONSTRAINT fk_benefit_carrier_policy_source_evidence
            FOREIGN KEY (sourceEvidenceId) REFERENCES rawBenefitSource(id);
    END IF;
END $$;
