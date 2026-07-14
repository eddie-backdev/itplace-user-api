-- 지도 클러스터용 매장 행정구역 사전 분류 (PostgreSQL + PostGIS)
--
-- StoreRepository가 지도 요청마다 주소를 정규식으로 분해하지 않도록
-- CITY/TOWN/LEGAL_DONG 요청별 최종 행정구역을 매장 단위로 저장한다.
-- 운영은 ddl-auto=validate 이므로 애플리케이션 배포 전에 실행한다.

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

CREATE TABLE IF NOT EXISTS map_store_cluster_region (
    store_id BIGINT PRIMARY KEY REFERENCES store(storeId) ON DELETE CASCADE,
    city_region_key VARCHAR(300),
    city_region_name VARCHAR(100),
    town_region_type VARCHAR(20) NOT NULL,
    town_region_key VARCHAR(300),
    town_region_name VARCHAR(100),
    legal_dong_region_type VARCHAR(20) NOT NULL,
    legal_dong_region_key VARCHAR(300),
    legal_dong_region_name VARCHAR(100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (town_region_type IN ('CITY', 'TOWN')),
    CHECK (legal_dong_region_type IN ('CITY', 'TOWN', 'LEGAL_DONG'))
);

COMMENT ON TABLE map_store_cluster_region IS
    '지도 클러스터 조회 시 반복적인 주소 파싱을 제거하기 위한 매장별 행정구역 분류';

CREATE OR REPLACE FUNCTION resolve_map_store_cluster_region(
    source_address TEXT,
    source_city TEXT,
    source_town TEXT,
    source_legal_dong TEXT
)
RETURNS TABLE (
    city_region_key VARCHAR(300),
    city_region_name VARCHAR(100),
    town_region_type VARCHAR(20),
    town_region_key VARCHAR(300),
    town_region_name VARCHAR(100),
    legal_dong_region_type VARCHAR(20),
    legal_dong_region_key VARCHAR(300),
    legal_dong_region_name VARCHAR(100)
)
LANGUAGE SQL
IMMUTABLE
PARALLEL SAFE
AS $$
    WITH address_parts AS (
        SELECT regexp_split_to_array(
            BTRIM(COALESCE(source_address, '')),
            '[[:space:]]+'
        ) AS parts
    ),
    raw_city AS (
        SELECT
            parts,
            CASE
                WHEN parts[1] IN (
                    '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종',
                    '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주',
                    '서울시', '부산시', '대구시', '인천시', '광주시', '대전시', '울산시', '세종시'
                ) OR parts[1] ~ '(특별시|광역시|특별자치시|특별자치도|도)$'
                    THEN parts[1]
                ELSE NULLIF(BTRIM(source_city), '')
            END AS name,
            (
                parts[1] IN (
                    '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종',
                    '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주',
                    '서울시', '부산시', '대구시', '인천시', '광주시', '대전시', '울산시', '세종시'
                ) OR parts[1] ~ '(특별시|광역시|특별자치시|특별자치도|도)$'
            ) AS address_is_full
        FROM address_parts
    ),
    normalized_city AS (
        SELECT
            parts,
            address_is_full,
            CASE name
                WHEN '서울시' THEN '서울'
                WHEN '서울특별시' THEN '서울'
                WHEN '부산시' THEN '부산'
                WHEN '부산광역시' THEN '부산'
                WHEN '대구시' THEN '대구'
                WHEN '대구광역시' THEN '대구'
                WHEN '인천시' THEN '인천'
                WHEN '인천광역시' THEN '인천'
                WHEN '광주시' THEN '광주'
                WHEN '광주광역시' THEN '광주'
                WHEN '대전시' THEN '대전'
                WHEN '대전광역시' THEN '대전'
                WHEN '울산시' THEN '울산'
                WHEN '울산광역시' THEN '울산'
                WHEN '세종시' THEN '세종'
                WHEN '세종특별자치시' THEN '세종'
                WHEN '경기도' THEN '경기'
                WHEN '강원도' THEN '강원'
                WHEN '강원특별자치도' THEN '강원'
                WHEN '충청북도' THEN '충북'
                WHEN '충청남도' THEN '충남'
                WHEN '전라북도' THEN '전북'
                WHEN '전북특별자치도' THEN '전북'
                WHEN '전라남도' THEN '전남'
                WHEN '경상북도' THEN '경북'
                WHEN '경상남도' THEN '경남'
                WHEN '제주도' THEN '제주'
                WHEN '제주특별자치도' THEN '제주'
                ELSE name
            END AS city
        FROM raw_city
    ),
    parsed_region AS (
        SELECT
            normalized_city.city,
            COALESCE(
                NULLIF(BTRIM((
                    SELECT string_agg(parts[part_index], ' ' ORDER BY part_index)
                    FROM generate_subscripts(parts, 1) part_index
                    WHERE part_index >= 2
                      AND part_index < administrative_dong.position
                )), ''),
                NULLIF(BTRIM(source_town), '')
            ) AS town,
            COALESCE(
                NULLIF(parts[administrative_dong.position], ''),
                NULLIF(BTRIM(source_legal_dong), '')
            ) AS legal_dong
        FROM normalized_city
        LEFT JOIN LATERAL (
            SELECT part_index AS position
            FROM generate_subscripts(parts, 1) part_index
            WHERE normalized_city.address_is_full
              AND part_index > 1
              AND parts[part_index] ~ '(동|읍|면|리|가)$'
            ORDER BY part_index DESC
            LIMIT 1
        ) administrative_dong ON TRUE
    )
    SELECT
        city::VARCHAR(300) AS city_region_key,
        city::VARCHAR(100) AS city_region_name,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(시|군|구)$' THEN 'TOWN'
            WHEN town ~ '(시|군|구|읍|면)$' THEN 'TOWN'
            ELSE 'CITY'
        END::VARCHAR(20) AS town_region_type,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(시|군|구)$'
                THEN CONCAT_WS('|', city, town, legal_dong)
            WHEN town ~ '(시|군|구|읍|면)$'
                THEN CONCAT_WS('|', city, town)
            ELSE city
        END::VARCHAR(300) AS town_region_key,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(시|군|구)$' THEN legal_dong
            WHEN town ~ '(시|군|구|읍|면)$' THEN town
            ELSE city
        END::VARCHAR(100) AS town_region_name,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(동|읍|면|리|가)$' THEN 'LEGAL_DONG'
            WHEN town ~ '(동|읍|면|리|가)$' THEN 'LEGAL_DONG'
            WHEN town IS NOT NULL AND legal_dong ~ '(시|군|구)$' THEN 'TOWN'
            WHEN town ~ '(시|군|구|읍|면)$' THEN 'TOWN'
            ELSE 'CITY'
        END::VARCHAR(20) AS legal_dong_region_type,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(동|읍|면|리|가)$'
                THEN CONCAT_WS('|', city, town, legal_dong)
            WHEN town ~ '(동|읍|면|리|가)$'
                THEN CONCAT_WS('|', city, town)
            WHEN town IS NOT NULL AND legal_dong ~ '(시|군|구)$'
                THEN CONCAT_WS('|', city, town, legal_dong)
            WHEN town ~ '(시|군|구|읍|면)$'
                THEN CONCAT_WS('|', city, town)
            ELSE city
        END::VARCHAR(300) AS legal_dong_region_key,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(동|읍|면|리|가)$' THEN legal_dong
            WHEN town ~ '(동|읍|면|리|가)$' THEN town
            WHEN town IS NOT NULL AND legal_dong ~ '(시|군|구)$' THEN legal_dong
            WHEN town ~ '(시|군|구|읍|면)$' THEN town
            ELSE city
        END::VARCHAR(100) AS legal_dong_region_name
    FROM parsed_region;
$$;

CREATE OR REPLACE FUNCTION sync_map_store_cluster_region()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO map_store_cluster_region (
        store_id,
        city_region_key,
        city_region_name,
        town_region_type,
        town_region_key,
        town_region_name,
        legal_dong_region_type,
        legal_dong_region_key,
        legal_dong_region_name,
        updated_at
    )
    SELECT
        NEW.storeId,
        resolved.city_region_key,
        resolved.city_region_name,
        resolved.town_region_type,
        resolved.town_region_key,
        resolved.town_region_name,
        resolved.legal_dong_region_type,
        resolved.legal_dong_region_key,
        resolved.legal_dong_region_name,
        CURRENT_TIMESTAMP
    FROM resolve_map_store_cluster_region(
        NEW.address,
        NEW.city,
        NEW.town,
        NEW.legalDong
    ) resolved
    ON CONFLICT (store_id) DO UPDATE SET
        city_region_key = EXCLUDED.city_region_key,
        city_region_name = EXCLUDED.city_region_name,
        town_region_type = EXCLUDED.town_region_type,
        town_region_key = EXCLUDED.town_region_key,
        town_region_name = EXCLUDED.town_region_name,
        legal_dong_region_type = EXCLUDED.legal_dong_region_type,
        legal_dong_region_key = EXCLUDED.legal_dong_region_key,
        legal_dong_region_name = EXCLUDED.legal_dong_region_name,
        updated_at = EXCLUDED.updated_at;

    INSERT INTO map_region_anchor (
        region_type,
        region_key,
        region_name,
        latitude,
        longitude,
        anchor_source
    )
    SELECT DISTINCT
        region.region_type,
        region.region_key,
        region.region_name,
        NEW.latitude::DOUBLE PRECISION,
        NEW.longitude::DOUBLE PRECISION,
        'FIRST_STORE'
    FROM map_store_cluster_region mapped
    CROSS JOIN LATERAL (
        VALUES
            ('CITY', mapped.city_region_key, mapped.city_region_name),
            (mapped.town_region_type, mapped.town_region_key, mapped.town_region_name),
            (
                mapped.legal_dong_region_type,
                mapped.legal_dong_region_key,
                mapped.legal_dong_region_name
            )
    ) region(region_type, region_key, region_name)
    WHERE mapped.store_id = NEW.storeId
      AND region.region_key IS NOT NULL
      AND NEW.latitude IS NOT NULL
      AND NEW.longitude IS NOT NULL
    ON CONFLICT (region_type, region_key) DO NOTHING;

    RETURN NEW;
END;
$$;

LOCK TABLE store IN SHARE ROW EXCLUSIVE MODE;

DROP TRIGGER IF EXISTS trg_sync_map_store_cluster_region ON store;
CREATE TRIGGER trg_sync_map_store_cluster_region
AFTER INSERT OR UPDATE OF address, city, town, legalDong, latitude, longitude
ON store
FOR EACH ROW
EXECUTE FUNCTION sync_map_store_cluster_region();

COMMIT;

-- 트리거를 먼저 활성화하고 장시간 백필은 별도 트랜잭션에서 수행한다.
-- 백필 도중 갱신된 매장은 트리거의 더 최신 updated_at을 보존한다.
BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

INSERT INTO map_store_cluster_region (
    store_id,
    city_region_key,
    city_region_name,
    town_region_type,
    town_region_key,
    town_region_name,
    legal_dong_region_type,
    legal_dong_region_key,
    legal_dong_region_name,
    updated_at
)
SELECT
    s.storeId,
    resolved.city_region_key,
    resolved.city_region_name,
    resolved.town_region_type,
    resolved.town_region_key,
    resolved.town_region_name,
    resolved.legal_dong_region_type,
    resolved.legal_dong_region_key,
    resolved.legal_dong_region_name,
    CURRENT_TIMESTAMP
FROM store s
CROSS JOIN LATERAL resolve_map_store_cluster_region(
    s.address,
    s.city,
    s.town,
    s.legalDong
) resolved
WHERE s.location IS NOT NULL
ON CONFLICT (store_id) DO UPDATE SET
    city_region_key = EXCLUDED.city_region_key,
    city_region_name = EXCLUDED.city_region_name,
    town_region_type = EXCLUDED.town_region_type,
    town_region_key = EXCLUDED.town_region_key,
    town_region_name = EXCLUDED.town_region_name,
    legal_dong_region_type = EXCLUDED.legal_dong_region_type,
    legal_dong_region_key = EXCLUDED.legal_dong_region_key,
    legal_dong_region_name = EXCLUDED.legal_dong_region_name,
    updated_at = EXCLUDED.updated_at
WHERE map_store_cluster_region.updated_at <= EXCLUDED.updated_at;

ANALYZE map_store_cluster_region;

COMMIT;
