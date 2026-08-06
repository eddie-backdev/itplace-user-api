-- 지도 클러스터 요청에서 전체 매장을 반복 집계하지 않도록 행정구역별 매장 수를 사전 계산한다.
-- aggregation_unit은 요청한 집계 단계이고 region_type은 주소 누락 시 실제 fallback된 행정구역 단계다.

CREATE MATERIALIZED VIEW map_region_store_summary AS
WITH eligible_partner AS MATERIALIZED (
    SELECT DISTINCT b.partnerId
    FROM benefit b
    JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
    WHERE COALESCE(b.active, true) = true
      AND COALESCE(bcp.active, true) = true
      AND bcp.usageType IN ('offline', 'both')
),
eligible_store AS MATERIALIZED (
    SELECT
        s.storeId,
        COALESCE(p.category, '') AS category
    FROM store s
    JOIN partner p ON p.partnerId = s.partnerId
    JOIN eligible_partner eligible ON eligible.partnerId = s.partnerId
    WHERE s.location IS NOT NULL
      AND s.active = true
      AND (
          REGEXP_REPLACE(
              LOWER(COALESCE(p.partnerName, '')),
              '[^가-힣a-z0-9]+',
              '',
              'g'
          ) NOT IN ('다락', '미니창고다락')
          OR s.business LIKE '%보관%'
          OR s.business LIKE '%저장%'
      )
),
region_member AS (
    SELECT
        'CITY'::VARCHAR(20) AS aggregation_unit,
        'CITY'::VARCHAR(20) AS region_type,
        mapped.city_region_key AS region_key,
        mapped.city_region_name AS region_name,
        mapped.city_region_hash AS region_hash,
        store.category
    FROM eligible_store store
    JOIN map_store_cluster_region mapped ON mapped.store_id = store.storeId

    UNION ALL

    SELECT
        'TOWN'::VARCHAR(20) AS aggregation_unit,
        mapped.town_region_type AS region_type,
        mapped.town_region_key AS region_key,
        mapped.town_region_name AS region_name,
        mapped.town_region_hash AS region_hash,
        store.category
    FROM eligible_store store
    JOIN map_store_cluster_region mapped ON mapped.store_id = store.storeId

    UNION ALL

    SELECT
        'LEGAL_DONG'::VARCHAR(20) AS aggregation_unit,
        mapped.legal_dong_region_type AS region_type,
        mapped.legal_dong_region_key AS region_key,
        mapped.legal_dong_region_name AS region_name,
        mapped.legal_dong_region_hash AS region_hash,
        store.category
    FROM eligible_store store
    JOIN map_store_cluster_region mapped ON mapped.store_id = store.storeId
)
SELECT
    aggregation_unit,
    region_type,
    region_hash,
    region_key,
    MIN(region_name) AS region_name,
    category,
    COUNT(*)::BIGINT AS store_count
FROM region_member
WHERE region_key IS NOT NULL
  AND region_hash IS NOT NULL
GROUP BY aggregation_unit, region_type, region_hash, region_key, category
WITH DATA;

CREATE UNIQUE INDEX uq_map_region_store_summary_lookup
    ON map_region_store_summary (
        aggregation_unit,
        region_type,
        region_hash,
        category
    );

ANALYZE map_region_store_summary;

COMMENT ON MATERIALIZED VIEW map_region_store_summary IS
    '지도 클러스터 조회용 행정구역·카테고리별 활성 오프라인 혜택 매장 수';

CREATE TABLE map_region_store_summary_state (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    last_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO map_region_store_summary_state (singleton)
VALUES (true);
