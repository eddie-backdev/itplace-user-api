-- 지도 행정구역 클러스터 고정 대표점 (PostgreSQL + PostGIS)
--
-- 운영은 ddl-auto=validate 이므로 애플리케이션 배포 전에 실행한다.
-- 최초 실행 시 현재 전체 매장 분포의 중심점을 지역별 대표점으로 저장한다.
-- ON CONFLICT DO NOTHING으로 기존 대표점은 유지되므로 화면 이동이나 매장 추가로 흔들리지 않는다.
-- 추후 공식 행정구역 경계의 ST_PointOnSurface 좌표를 확보하면 같은 PK로 UPDATE할 수 있다.

BEGIN;

CREATE TABLE IF NOT EXISTS map_region_anchor (
    region_type VARCHAR(20) NOT NULL,
    region_key VARCHAR(300) NOT NULL,
    region_name VARCHAR(100),
    latitude DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    anchor_source VARCHAR(30) NOT NULL DEFAULT 'STORE_CENTROID',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (region_type, region_key),
    CHECK (region_type IN ('CITY', 'TOWN', 'LEGAL_DONG'))
);

COMMENT ON TABLE map_region_anchor IS
    '지도 행정구역 클러스터가 viewport와 무관하게 사용하는 고정 대표점';
COMMENT ON COLUMN map_region_anchor.region_key IS
    'StoreRepository 행정구역 분류 키와 동일한 city|town|legalDong 조합';
COMMENT ON COLUMN map_region_anchor.anchor_source IS
    'STORE_CENTROID 또는 향후 BOUNDARY_POINT 등 대표점 출처';

WITH parsed_store AS MATERIALIZED (
    SELECT
        administrative_city.name AS city,
        COALESCE(
            NULLIF(BTRIM(administrative_town.name), ''),
            NULLIF(BTRIM(s.town), '')
        ) AS town,
        COALESCE(
            NULLIF(address_parts.parts[administrative_dong.position], ''),
            NULLIF(BTRIM(s.legalDong), '')
        ) AS legal_dong,
        ST_Transform(s.location::geometry, 3857) AS geom
    FROM store s
    CROSS JOIN LATERAL (
        SELECT regexp_split_to_array(
            BTRIM(COALESCE(s.address, '')),
            '[[:space:]]+'
        ) AS parts
    ) address_parts
    CROSS JOIN LATERAL (
        SELECT
            CASE
                WHEN address_parts.parts[1] IN (
                    '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종',
                    '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주',
                    '서울시', '부산시', '대구시', '인천시', '광주시', '대전시', '울산시', '세종시'
                ) OR address_parts.parts[1] ~ '(특별시|광역시|특별자치시|특별자치도|도)$'
                    THEN address_parts.parts[1]
                ELSE NULLIF(BTRIM(s.city), '')
            END AS raw_name,
            (
                address_parts.parts[1] IN (
                    '서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종',
                    '경기', '강원', '충북', '충남', '전북', '전남', '경북', '경남', '제주',
                    '서울시', '부산시', '대구시', '인천시', '광주시', '대전시', '울산시', '세종시'
                ) OR address_parts.parts[1] ~ '(특별시|광역시|특별자치시|특별자치도|도)$'
            ) AS address_is_full
    ) raw_city
    CROSS JOIN LATERAL (
        SELECT
            CASE raw_city.raw_name
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
                ELSE raw_city.raw_name
            END AS name,
            raw_city.address_is_full
    ) administrative_city
    LEFT JOIN LATERAL (
        SELECT part_index AS position
        FROM generate_subscripts(address_parts.parts, 1) part_index
        WHERE administrative_city.address_is_full
          AND part_index > 1
          AND address_parts.parts[part_index] ~ '(동|읍|면|리|가)$'
        ORDER BY part_index DESC
        LIMIT 1
    ) administrative_dong ON TRUE
    LEFT JOIN LATERAL (
        SELECT string_agg(address_parts.parts[town_index], ' ' ORDER BY town_index) AS name
        FROM generate_subscripts(address_parts.parts, 1) town_index
        WHERE town_index >= 2
          AND town_index < administrative_dong.position
    ) administrative_town ON TRUE
    WHERE s.location IS NOT NULL
),
region_member AS (
    SELECT
        'CITY'::VARCHAR AS region_type,
        city AS region_key,
        city AS region_name,
        geom
    FROM parsed_store
    WHERE city IS NOT NULL

    UNION ALL

    SELECT
        'TOWN'::VARCHAR AS region_type,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(시|군|구)$'
                THEN CONCAT_WS('|', city, town, legal_dong)
            WHEN town ~ '(시|군|구|읍|면)$'
                THEN CONCAT_WS('|', city, town)
        END AS region_key,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(시|군|구)$' THEN legal_dong
            WHEN town ~ '(시|군|구|읍|면)$' THEN town
        END AS region_name,
        geom
    FROM parsed_store
    WHERE city IS NOT NULL
      AND (
          (town IS NOT NULL AND legal_dong ~ '(시|군|구)$')
          OR town ~ '(시|군|구|읍|면)$'
      )

    UNION ALL

    SELECT
        'LEGAL_DONG'::VARCHAR AS region_type,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(동|읍|면|리|가)$'
                THEN CONCAT_WS('|', city, town, legal_dong)
            WHEN town ~ '(동|읍|면|리|가)$'
                THEN CONCAT_WS('|', city, town)
        END AS region_key,
        CASE
            WHEN town IS NOT NULL AND legal_dong ~ '(동|읍|면|리|가)$' THEN legal_dong
            WHEN town ~ '(동|읍|면|리|가)$' THEN town
        END AS region_name,
        geom
    FROM parsed_store
    WHERE city IS NOT NULL
      AND (
          (town IS NOT NULL AND legal_dong ~ '(동|읍|면|리|가)$')
          OR town ~ '(동|읍|면|리|가)$'
      )
),
region_summary AS (
    SELECT
        region_type,
        region_key,
        MIN(region_name) AS region_name,
        AVG(ST_X(geom)) AS centroid_x,
        AVG(ST_Y(geom)) AS centroid_y
    FROM region_member
    WHERE region_key IS NOT NULL
    GROUP BY region_type, region_key
)
INSERT INTO map_region_anchor (
    region_type,
    region_key,
    region_name,
    latitude,
    longitude,
    anchor_source
)
SELECT
    region_type,
    region_key,
    region_name,
    ST_Y(ST_Transform(
        ST_SetSRID(ST_MakePoint(centroid_x, centroid_y), 3857),
        4326
    )),
    ST_X(ST_Transform(
        ST_SetSRID(ST_MakePoint(centroid_x, centroid_y), 3857),
        4326
    )),
    'STORE_CENTROID'
FROM region_summary
ON CONFLICT (region_type, region_key) DO NOTHING;

COMMIT;
