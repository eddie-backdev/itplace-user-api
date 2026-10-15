-- 선택 유형은 실제 보상 종류(type)와 독립적이다. 원문명/식별 키/혜택 ID는 보존한다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '180s';

ALTER TABLE benefitCarrierPolicy
    ADD COLUMN IF NOT EXISTS membershipoptiontype VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS sourceoptionlabel VARCHAR(100);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'benefitcarrierpolicy'::regclass
          AND conname = 'ck_benefit_membership_option_type'
    ) THEN
        ALTER TABLE benefitCarrierPolicy ADD CONSTRAINT ck_benefit_membership_option_type
            CHECK (membershipoptiontype IN ('DISCOUNT', 'POINT_ACCRUAL', 'NOT_APPLICABLE', 'UNKNOWN'));
    END IF;
END
$$;

-- 기존 SKT 수집명의 정확한 마지막 구분만 백필한다. KT/LGU의 미수집은 UNKNOWN이다.
UPDATE benefitCarrierPolicy
SET membershipoptiontype = CASE
        WHEN carrierBenefitName ~ ' 할인형$' THEN 'DISCOUNT'
        ELSE 'POINT_ACCRUAL'
    END,
    sourceoptionlabel = CASE
        WHEN carrierBenefitName ~ ' 할인형$' THEN '할인형'
        ELSE '적립형'
    END
WHERE carrier = 'SKT'
  AND membershipoptiontype = 'UNKNOWN'
  AND carrierBenefitName ~ ' (할인형|적립형)$'
  AND (sourceoptionlabel IS NULL OR sourceoptionlabel IN ('할인형', '적립형'));

-- 예전 수집기는 선택 유형만 보고 적립형을 전부 증정으로 저장했다.
-- BenefitRewardTypeClassifier와 같은 명시적 보상 단어를 등급별 설명에서 확인한다.
-- 설명에서 판별되지 않는 보상은 미확인으로 남기며, 여러 종류는 복합으로 보존한다.
WITH descriptions AS (
    SELECT p.benefitCarrierPolicyId,
           COALESCE(string_agg(NULLIF(TRIM(t.context), ''), ' '), p.description, '') AS text
    FROM benefitCarrierPolicy p
    LEFT JOIN carrierTierBenefit t USING (benefitCarrierPolicyId)
    WHERE p.carrier = 'SKT'
      AND p.membershipoptiontype IN ('DISCOUNT', 'POINT_ACCRUAL')
    GROUP BY p.benefitCarrierPolicyId, p.description
), signals AS (
    SELECT benefitCarrierPolicyId,
           (text LIKE '%할인%')::int AS discount,
           (text LIKE '%적립%')::int AS accrual,
           (text ~ '포인트[[:space:]]*(로[[:space:]]*)?사용')::int AS point_use,
           (text ~ '무료|증정|사은품')::int AS free
    FROM descriptions
), classified AS (
    SELECT benefitCarrierPolicyId,
           CASE WHEN discount + accrual + point_use + free > 1 THEN '복합'
                WHEN discount = 1 THEN '할인'
                WHEN accrual = 1 THEN '적립'
                WHEN point_use = 1 THEN '포인트 사용'
                WHEN free = 1 THEN '증정'
                ELSE '미확인' END AS reward_type
    FROM signals
)
UPDATE benefitCarrierPolicy p
SET type = c.reward_type
FROM classified c
WHERE p.benefitCarrierPolicyId = c.benefitCarrierPolicyId
  AND p.type IS DISTINCT FROM c.reward_type;

-- 표시 규칙 변경을 지도/추천 캐시에도 반영한다. Admin 전용 DB에는 대기열이 없을 수 있다.
DO $$
BEGIN
    IF to_regclass('benefit_cache_refresh') IS NOT NULL THEN
        INSERT INTO benefit_cache_refresh(partner_id)
        SELECT DISTINCT partnerId FROM benefit WHERE partnerId IS NOT NULL
        ON CONFLICT (partner_id) DO UPDATE SET changed_at = clock_timestamp();
    END IF;
END
$$;
