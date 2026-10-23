CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE benefitCarrierPolicy
    ADD CONSTRAINT uq_benefit_carrier_policy_id_benefit
        UNIQUE (benefitCarrierPolicyId, benefitId);

ALTER TABLE carrierTierBenefit
    ADD CONSTRAINT uq_carrier_tier_benefit_id_policy
        UNIQUE (carrierTierBenefitId, benefitCarrierPolicyId);

CREATE TABLE benefit_search_document (
    document_id text PRIMARY KEY,
    benefitid bigint NOT NULL REFERENCES benefit(benefitId) ON DELETE CASCADE,
    benefitcarrierpolicyid bigint NOT NULL,
    carriertierbenefitid bigint,
    carrier text NOT NULL,
    grade text,
    is_all_grade boolean NOT NULL DEFAULT false,
    usage_type text,
    active boolean NOT NULL,
    searchable_text text NOT NULL,
    metadata jsonb NOT NULL,
    embedding vector(1536),
    embedding_model text NOT NULL,
    embedding_dimensions smallint NOT NULL CHECK (embedding_dimensions = 1536),
    embedding_version text NOT NULL,
    content_hash char(64) NOT NULL,
    source_updated_at timestamptz,
    indexed_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    CONSTRAINT fk_bsd_policy_benefit FOREIGN KEY (benefitcarrierpolicyid, benefitid)
        REFERENCES benefitCarrierPolicy(benefitCarrierPolicyId, benefitId) ON DELETE CASCADE,
    CONSTRAINT fk_bsd_tier_policy FOREIGN KEY (carriertierbenefitid, benefitcarrierpolicyid)
        REFERENCES carrierTierBenefit(carrierTierBenefitId, benefitCarrierPolicyId) ON DELETE CASCADE,
    CONSTRAINT uq_bsd_identity UNIQUE NULLS NOT DISTINCT
        (benefitid, benefitcarrierpolicyid, carriertierbenefitid),
    CONSTRAINT ck_bsd_document_identity CHECK (
        document_id = 'benefit:' || benefitid::text || ':policy:' || benefitcarrierpolicyid::text ||
            ':tier:' || coalesce(carriertierbenefitid::text, 'policy')
    ),
    CONSTRAINT ck_bsd_policy_tier_grade CHECK (carriertierbenefitid IS NOT NULL OR grade IS NULL)
);

CREATE INDEX ix_bsd_active_policy ON benefit_search_document (benefitcarrierpolicyid) WHERE active;
CREATE INDEX ix_bsd_search_text_trgm ON benefit_search_document USING gin (lower(searchable_text) gin_trgm_ops);
