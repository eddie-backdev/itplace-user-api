-- NULL은 기존 COALESCE(active, TRUE) 조회 계약대로 활성으로 정규화한다.
-- active=true partial index와 검색 조건을 일치시킨다.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

UPDATE benefit SET active = TRUE WHERE active IS NULL;
ALTER TABLE benefit ALTER COLUMN active SET DEFAULT TRUE, ALTER COLUMN active SET NOT NULL;
UPDATE benefitCarrierPolicy SET active = TRUE WHERE active IS NULL;
ALTER TABLE benefitCarrierPolicy ALTER COLUMN active SET DEFAULT TRUE, ALTER COLUMN active SET NOT NULL;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 서로 다른 테이블에 걸친 OR를 매장 후보/제휴사 후보로 나눈 검색의 매장 부분.
-- 한글·부분 일치·LIKE wildcard 의미를 유지한다. 짧은 검색어는 seq scan이 더 쌀 수 있다.
CREATE INDEX IF NOT EXISTS idx_store_name_search
    ON store USING GIN (LOWER(COALESCE(storeName, '')) gin_trgm_ops)
    WHERE active = TRUE AND location IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_store_business_search
    ON store USING GIN (LOWER(COALESCE(business, '')) gin_trgm_ops)
    WHERE active = TRUE AND location IS NOT NULL;

-- 기존 PK(benefitId,userId)는 사용자별 목록과 최근 관심 변경 검사를 지원하지 못한다.
CREATE INDEX IF NOT EXISTS idx_favorite_user_created
    ON favorite (userId, createdDate DESC);

-- 과거 스키마에서 PK 대신 참조되던 unique index는 유지한다. 현재 PK와 동일하고
-- FK/constraint/replica identity 의존성이 없을 때만 중복 인덱스를 제거한다.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_index duplicate
        JOIN pg_index primary_index ON primary_index.indrelid = duplicate.indrelid
            AND primary_index.indisprimary AND primary_index.indkey = duplicate.indkey
        WHERE duplicate.indexrelid = to_regclass('uq_benefit_carrier_policy_id')
          AND NOT duplicate.indisreplident
          AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conindid = duplicate.indexrelid)
    ) THEN
        DROP INDEX uq_benefit_carrier_policy_id;
    END IF;
END $$;

ANALYZE store;
ANALYZE benefit;
ANALYZE benefitCarrierPolicy;
