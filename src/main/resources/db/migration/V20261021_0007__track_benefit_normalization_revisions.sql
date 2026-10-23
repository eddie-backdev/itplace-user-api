CREATE TABLE IF NOT EXISTS benefitNormalizationRevision (
    id BIGSERIAL PRIMARY KEY,
    policyId BIGINT NOT NULL REFERENCES benefitCarrierPolicy(benefitcarrierpolicyid) ON DELETE CASCADE,
    sourceEvidenceId BIGINT REFERENCES rawBenefitSource(id),
    sourceCapturedAt TIMESTAMPTZ,
    normalizerVersion VARCHAR(80) NOT NULL,
    normalizedAt TIMESTAMPTZ NOT NULL,
    snapshotHash VARCHAR(64) NOT NULL,
    beforeConditions JSONB,
    afterConditions JSONB NOT NULL,
    beforeTiers JSONB,
    afterTiers JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_benefit_normalization_revision_policy
    ON benefitNormalizationRevision (policyId, normalizedAt DESC, id DESC);
