-- 운영은 ddl-auto=validate이므로 admin-api/user-api 배포 전에 실행한다.
-- 기존 매장은 우선 활성 상태로 유지하고, 이후 정상 지오데이터 수집부터 발견 이력을 누적한다.

ALTER TABLE store
    ADD COLUMN IF NOT EXISTS sourceProvider VARCHAR(32),
    ADD COLUMN IF NOT EXISTS sourcePlaceId VARCHAR(128),
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS lastSeenAt TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lastSeenRunId VARCHAR(36),
    ADD COLUMN IF NOT EXISTS healthyMissCount INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS inactivatedAt TIMESTAMPTZ;

UPDATE store
SET active = TRUE
WHERE active IS NULL;

UPDATE store
SET healthyMissCount = 0
WHERE healthyMissCount IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_store_kakao_partner_place
    ON store (partnerId, sourcePlaceId)
    WHERE sourceProvider = 'KAKAO' AND sourcePlaceId IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_store_partner_active
    ON store (partnerId, active);

CREATE INDEX IF NOT EXISTS idx_store_last_seen_run
    ON store (partnerId, lastSeenRunId)
    WHERE active = TRUE;

CREATE INDEX IF NOT EXISTS idx_benefit_active_partner
    ON benefit (partnerId)
    WHERE active = TRUE;

CREATE INDEX IF NOT EXISTS idx_benefit_policy_active_offline
    ON benefitCarrierPolicy (benefitId)
    WHERE active = TRUE AND usageType IN ('offline', 'both');

ANALYZE store;
ANALYZE benefit;
ANALYZE benefitCarrierPolicy;
