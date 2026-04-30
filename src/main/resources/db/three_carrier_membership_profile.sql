-- 통신 3사 멤버십 1차 확장 수동 SQL (PostgreSQL)
-- prod는 ddl-auto=validate 이므로 배포 전 적용한다.
-- 멤버십 번호는 삭제하지 않고 legacy/internal 용도로만 유지한다.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS carrier VARCHAR(10),
    ADD COLUMN IF NOT EXISTS "membershipGradeCode" VARCHAR(30),
    ADD COLUMN IF NOT EXISTS "membershipVerified" BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE benefit
    ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS "sourceKey" VARCHAR(255),
    ADD COLUMN IF NOT EXISTS "sourceCategory" VARCHAR(100),
    ADD COLUMN IF NOT EXISTS "lastCrawledAt" TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uq_benefit_carrier_source_key
    ON benefit (carrier, "sourceKey")
    WHERE "sourceKey" IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_benefit_active_carrier
    ON benefit (active, carrier);
