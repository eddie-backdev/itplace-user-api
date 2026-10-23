ALTER TABLE benefit_search_document
    ADD COLUMN offer_key text;

ALTER TABLE carrierTierBenefit
    ADD CONSTRAINT uq_carrier_tier_benefit_id_policy_grade
        UNIQUE (carrierTierBenefitId, benefitCarrierPolicyId, grade);

ALTER TABLE benefit_search_document
    DROP CONSTRAINT uq_bsd_identity,
    DROP CONSTRAINT ck_bsd_document_identity,
    DROP CONSTRAINT ck_bsd_policy_tier_grade,
    DROP CONSTRAINT fk_bsd_tier_policy,
    ADD CONSTRAINT fk_bsd_tier_policy_grade FOREIGN KEY
        (carriertierbenefitid, benefitcarrierpolicyid, grade)
        REFERENCES carrierTierBenefit(carrierTierBenefitId, benefitCarrierPolicyId, grade) ON DELETE CASCADE,
    ADD CONSTRAINT uq_bsd_identity UNIQUE NULLS NOT DISTINCT
        (benefitid, benefitcarrierpolicyid, carriertierbenefitid, offer_key, grade),
    ADD CONSTRAINT ck_bsd_identity_shape CHECK (
        (carriertierbenefitid IS NOT NULL AND grade IS NOT NULL)
        OR (carriertierbenefitid IS NULL AND offer_key IS NOT NULL AND grade IS NOT NULL
            AND offer_key ~ '^[A-Za-z0-9_-]{1,80}$')
        OR (carriertierbenefitid IS NULL AND offer_key IS NULL AND grade IS NULL)
    ),
    ADD CONSTRAINT ck_bsd_document_identity CHECK (
        document_id = 'benefit:' || benefitid::text || ':policy:' || benefitcarrierpolicyid::text ||
            CASE WHEN carriertierbenefitid IS NOT NULL THEN ':tier:' || carriertierbenefitid::text
                 WHEN offer_key IS NOT NULL THEN ':offer:' || offer_key || ':grade:' || grade
                 ELSE ':tier:policy' END
    ),
    ADD CONSTRAINT ck_bsd_metadata_identity CHECK (
        metadata->>'benefitId' IS NOT DISTINCT FROM benefitid::text
        AND metadata->>'policyId' IS NOT DISTINCT FROM benefitcarrierpolicyid::text
        AND metadata->>'tierBenefitId' IS NOT DISTINCT FROM carriertierbenefitid::text
        AND metadata->>'offerKey' IS NOT DISTINCT FROM offer_key
        AND metadata->>'grade' IS NOT DISTINCT FROM grade
    );

-- Replacement deletes every document for a policy, including inactive rows.
CREATE INDEX ix_bsd_policy ON benefit_search_document (benefitcarrierpolicyid);
