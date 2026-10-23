-- 지도 클러스터 요약은 이용 채널이 아닌 실제 매장 존재 여부를 기준으로 계산한다.
-- 활성 혜택이 있는 파트너의 활성 매장을 채널 구분 없이 집계한다.

DO $$
BEGIN
    IF to_regclass('map_region_store_summary') IS NOT NULL THEN
        EXECUTE 'DROP MATERIALIZED VIEW map_region_store_summary';
        EXECUTE $view$
CREATE MATERIALIZED VIEW map_region_store_summary AS
WITH eligible_partner AS MATERIALIZED (
    SELECT DISTINCT b.partnerId
    FROM benefit b
    JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
    WHERE COALESCE(b.active, true) = true
      AND COALESCE(bcp.active, true) = true
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
        $view$;
        EXECUTE 'CREATE UNIQUE INDEX uq_map_region_store_summary_lookup
            ON map_region_store_summary (aggregation_unit, region_type, region_hash, category)';
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_map_region_store_summary_cluster_lookup
            ON map_region_store_summary (aggregation_unit, category, region_type, region_key)
            INCLUDE (region_hash, region_name, store_count)';
        EXECUTE 'ANALYZE map_region_store_summary';
        EXECUTE $comment$ COMMENT ON MATERIALIZED VIEW map_region_store_summary IS
            '지도 클러스터 조회용 행정구역·카테고리별 활성 혜택 파트너의 매장 수' $comment$;
        UPDATE map_region_store_summary_state
        SET last_refreshed_at = CURRENT_TIMESTAMP
        WHERE singleton = true;
    END IF;
END $$;
