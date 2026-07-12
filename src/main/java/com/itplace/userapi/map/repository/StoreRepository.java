package com.itplace.userapi.map.repository;

import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.projection.StorePreviewProjection;
import com.itplace.userapi.map.repository.projection.StoreClusterProjection;
import io.lettuce.core.dynamic.annotation.Param;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StoreRepository extends JpaRepository<Store, Long> {

    @Query(
            value = """
                    SELECT storeId FROM store
                    WHERE ST_DWithin(
                        location::geography,
                        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                        :radiusMeters
                    )
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<Long> findStoreIdsInRadius(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("limit") int limit
    );

    @Query(
            value = """
                    SELECT storeId FROM store
                    WHERE
                        longitude BETWEEN :minLng AND :maxLng
                        AND latitude BETWEEN :minLat AND :maxLat
                    LIMIT :limit
""",
            nativeQuery = true
    )
    List<Long> findStoreIdsInBounds(
            @Param("minLat") double minLat,
            @Param("maxLat") double maxLat,
            @Param("minLng") double minLng,
            @Param("maxLng") double maxLng,
            @Param("limit") int limit
    );

    @Query(
            value = """
                    SELECT s.storeId
                    FROM store s
                    JOIN partner p ON s.partnerId = p.partnerId
                    WHERE p.category = :category
                    AND ST_DWithin(
                        s.location::geography,
                        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                        :radiusMeters
                    )
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<Long> findStoreIdsByCategoryWithinRadius(
            @Param("category") String category,
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("limit") int limit
    );

    @Query(
            value = """
                    WITH candidate_store AS MATERIALIZED (
                        SELECT
                            s.storeId AS store_id,
                            FLOOR((s.latitude::double precision + 90.0) / :cellLatDegrees)::bigint AS grid_y,
                            FLOOR((s.longitude::double precision + 180.0) / :cellLngDegrees)::bigint AS grid_x,
                            ST_DistanceSphere(
                                s.location::geometry,
                                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
                            ) AS distance_meters
                        FROM store s
                        JOIN partner p ON s.partnerId = p.partnerId
                        WHERE s.location IS NOT NULL
                          AND (:category IS NULL OR p.category = :category)
                          AND s.location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
                          AND s.longitude BETWEEN :minLng AND :maxLng
                          AND s.latitude BETWEEN :minLat AND :maxLat
                          AND ST_DWithin(
                              s.location::geography,
                              ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                              :radiusMeters
                          )
                    ),
                    ranked_store AS (
                        SELECT
                            store_id,
                            grid_y,
                            grid_x,
                            distance_meters,
                            ROW_NUMBER() OVER (
                                PARTITION BY grid_y, grid_x
                                ORDER BY distance_meters ASC, store_id ASC
                            ) AS cell_rank
                        FROM candidate_store
                    )
                    SELECT store_id
                    FROM ranked_store
                    WHERE cell_rank <= :storesPerCell
                    ORDER BY distance_meters ASC, store_id ASC
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<Long> findDistributedStoreIdsWithinRadius(
            @Param("category") String category,
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("minLat") double minLat,
            @Param("maxLat") double maxLat,
            @Param("minLng") double minLng,
            @Param("maxLng") double maxLng,
            @Param("cellLatDegrees") double cellLatDegrees,
            @Param("cellLngDegrees") double cellLngDegrees,
            @Param("storesPerCell") int storesPerCell,
            @Param("limit") int limit
    );

    @Query(
            value = """
                    SELECT
                        s.storeId AS "storeId",
                        p.partnerId AS "partnerId",
                        s.storeName AS "storeName",
                        p.partnerName AS "partnerName",
                        p.category AS "category",
                        p.image AS "image",
                        ST_Y(s.location::geometry) AS "latitude",
                        ST_X(s.location::geometry) AS "longitude",
                        s.address AS "address",
                        s.roadName AS "roadName",
                        s.roadAddress AS "roadAddress",
                        s.postCode AS "postCode",
                        s.hasCoupon AS "hasCoupon"
                    FROM store s
                    JOIN partner p ON s.partnerId = p.partnerId
                    WHERE s.location IS NOT NULL
                      AND (:category IS NULL OR p.category = :category)
                      AND s.longitude BETWEEN :minLng AND :maxLng
                      AND s.latitude BETWEEN :minLat AND :maxLat
                    ORDER BY
                      ABS(s.latitude - :centerLat) + ABS(s.longitude - :centerLng) ASC,
                      s.storeId ASC
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<StorePreviewProjection> findStorePreviewsInView(
            @Param("minLat") double minLat,
            @Param("maxLat") double maxLat,
            @Param("minLng") double minLng,
            @Param("maxLng") double maxLng,
            @Param("centerLat") double centerLat,
            @Param("centerLng") double centerLng,
            @Param("category") String category,
            @Param("limit") int limit
    );



    @Query(
            value = """
                    WITH filtered_store AS MATERIALIZED (
                        SELECT
                            s.storeId AS store_id,
                            administrative_city.name AS city,
                            COALESCE(
                                NULLIF(BTRIM(administrative_town.name), ''),
                                NULLIF(BTRIM(s.town), '')
                            ) AS town,
                            COALESCE(
                                NULLIF(address_parts.parts[administrative_dong.position], ''),
                                NULLIF(BTRIM(s.legalDong), '')
                            ) AS legal_dong,
                            s.latitude::double precision AS latitude,
                            s.longitude::double precision AS longitude,
                            ST_Transform(s.location::geometry, 3857) AS geom
                        FROM store s
                        JOIN partner p ON s.partnerId = p.partnerId
                        CROSS JOIN LATERAL (
                            SELECT regexp_split_to_array(
                                BTRIM(COALESCE(
                                    CASE WHEN :administrativeUnitType = 'CITY' THEN '' ELSE s.address END,
                                    ''
                                )), '[[:space:]]+'
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
                            WHERE :administrativeUnitType <> 'CITY'
                              AND administrative_city.address_is_full
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
                          AND (:category IS NULL OR p.category = :category)
                          AND s.location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
                          AND s.longitude BETWEEN :minLng AND :maxLng
                          AND s.latitude BETWEEN :minLat AND :maxLat
                    ),
                    located_store AS (
                        SELECT
                            store_id,
                            city,
                            town,
                            legal_dong,
                            latitude,
                            longitude,
                            ST_X(geom) AS map_x,
                            ST_Y(geom) AS map_y,
                            FLOOR(ST_X(geom) / :gridSizeMeters)::bigint AS grid_x,
                            FLOOR(ST_Y(geom) / :gridSizeMeters)::bigint AS grid_y
                        FROM filtered_store
                    ),
                    classified_store AS MATERIALIZED (
                        SELECT
                            store_id,
                            latitude,
                            longitude,
                            map_x,
                            map_y,
                            CASE
                                WHEN :administrativeUnitType = 'LEGAL_DONG'
                                  AND city IS NOT NULL
                                  AND town IS NOT NULL
                                  AND legal_dong ~ '(동|읍|면|리|가)$'
                                    THEN 'LEGAL_DONG'
                                WHEN :administrativeUnitType = 'LEGAL_DONG'
                                  AND city IS NOT NULL
                                  AND town IS NOT NULL
                                  AND town ~ '(동|읍|면|리|가)$'
                                    THEN 'LEGAL_DONG'
                                WHEN :administrativeUnitType IN ('LEGAL_DONG', 'TOWN')
                                  AND city IS NOT NULL
                                  AND town IS NOT NULL
                                  AND legal_dong ~ '(시|군|구)$'
                                    THEN 'TOWN'
                                WHEN :administrativeUnitType IN ('LEGAL_DONG', 'TOWN')
                                  AND city IS NOT NULL
                                  AND town ~ '(시|군|구|읍|면)$'
                                    THEN 'TOWN'
                                WHEN city IS NOT NULL
                                    THEN 'CITY'
                                ELSE 'GRID'
                            END AS region_type,
                            CASE
                                WHEN :administrativeUnitType = 'LEGAL_DONG'
                                  AND city IS NOT NULL
                                  AND town IS NOT NULL
                                  AND legal_dong ~ '(동|읍|면|리|가)$'
                                    THEN CONCAT_WS('|', city, town, legal_dong)
                                WHEN :administrativeUnitType = 'LEGAL_DONG'
                                  AND city IS NOT NULL
                                  AND town IS NOT NULL
                                  AND town ~ '(동|읍|면|리|가)$'
                                    THEN CONCAT_WS('|', city, town)
                                WHEN :administrativeUnitType IN ('LEGAL_DONG', 'TOWN')
                                  AND city IS NOT NULL
                                  AND town IS NOT NULL
                                  AND legal_dong ~ '(시|군|구)$'
                                    THEN CONCAT_WS('|', city, town, legal_dong)
                                WHEN :administrativeUnitType IN ('LEGAL_DONG', 'TOWN')
                                  AND city IS NOT NULL
                                  AND town ~ '(시|군|구|읍|면)$'
                                    THEN CONCAT_WS('|', city, town)
                                WHEN city IS NOT NULL
                                    THEN city
                                ELSE CONCAT('grid|', grid_x, '|', grid_y)
                            END AS region_key,
                            CASE
                                WHEN :administrativeUnitType = 'LEGAL_DONG'
                                  AND city IS NOT NULL
                                  AND town IS NOT NULL
                                  AND legal_dong ~ '(동|읍|면|리|가)$'
                                    THEN legal_dong
                                WHEN :administrativeUnitType = 'LEGAL_DONG'
                                  AND city IS NOT NULL
                                  AND town IS NOT NULL
                                  AND town ~ '(동|읍|면|리|가)$'
                                    THEN town
                                WHEN :administrativeUnitType IN ('LEGAL_DONG', 'TOWN')
                                  AND city IS NOT NULL
                                  AND town IS NOT NULL
                                  AND legal_dong ~ '(시|군|구)$'
                                    THEN legal_dong
                                WHEN :administrativeUnitType IN ('LEGAL_DONG', 'TOWN')
                                  AND city IS NOT NULL
                                  AND town ~ '(시|군|구|읍|면)$'
                                    THEN town
                                WHEN city IS NOT NULL
                                    THEN city
                                ELSE NULL
                            END AS region_name
                        FROM located_store
                    ),
                    region_summary AS (
                        SELECT
                            region_type,
                            region_key,
                            MIN(region_name) AS region_name,
                            MIN(latitude) AS singleton_latitude,
                            MIN(longitude) AS singleton_longitude,
                            AVG(map_x) AS centroid_x,
                            AVG(map_y) AS centroid_y,
                            COUNT(*) AS store_count
                        FROM classified_store
                        GROUP BY region_type, region_key
                    )
                    SELECT
                        CONCAT('a:', :mapLevel, ':', region_type, ':', MD5(region_key)) AS "clusterId",
                        COALESCE(:category, '전체') AS "category",
                        region_type AS "administrativeUnitType",
                        region_name AS "administrativeUnitName",
                        CASE
                            WHEN store_count = 1 THEN singleton_latitude
                            ELSE ST_Y(ST_Transform(
                                ST_SetSRID(ST_MakePoint(centroid_x, centroid_y), 3857),
                                4326
                            ))
                        END AS "latitude",
                        CASE
                            WHEN store_count = 1 THEN singleton_longitude
                            ELSE ST_X(ST_Transform(
                                ST_SetSRID(ST_MakePoint(centroid_x, centroid_y), 3857),
                                4326
                            ))
                        END AS "longitude",
                        store_count AS "count"
                    FROM region_summary
                    ORDER BY store_count DESC, region_type ASC, region_key ASC
                    """,
            nativeQuery = true
    )
    List<StoreClusterProjection> findStoreClustersInView(
            @Param("minLat") double minLat,
            @Param("maxLat") double maxLat,
            @Param("minLng") double minLng,
            @Param("maxLng") double maxLng,
            @Param("category") String category,
            @Param("mapLevel") int mapLevel,
            @Param("administrativeUnitType") String administrativeUnitType,
            @Param("gridSizeMeters") double gridSizeMeters
    );


    @Query("SELECT s FROM Store s JOIN FETCH s.partner WHERE s.storeId IN :storeIds")
    List<Store> findAllByStoreIdInWithPartner(@Param("storeIds") List<Long> storeIds);

    @Query(
            value = """
                    SELECT s.storeId
                    FROM store s
                    JOIN partner p ON s.partnerId = p.partnerId
                    WHERE s.location IS NOT NULL
                    AND (:category IS NULL OR p.category = :category)
                    AND (
                        LOWER(COALESCE(s.storeName, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                        OR LOWER(COALESCE(s.business, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                        OR LOWER(COALESCE(p.partnerName, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                        OR LOWER(COALESCE(p.category, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                    )
                    ORDER BY
                           CASE
                               WHEN LOWER(COALESCE(s.storeName, '')) = LOWER(:keyword)
                                    OR LOWER(COALESCE(p.partnerName, '')) = LOWER(:keyword)
                               THEN 1 ELSE 0
                           END DESC,
                           ST_DistanceSphere(
                               s.location::geometry,
                               ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
                           ) ASC
                    LIMIT 30
                    """,
            nativeQuery = true
    )
    List<Long> searchNearbyStoreIds(@Param("lng") double lng, @Param("lat") double lat,
                                    @Param("category") String category, @Param("keyword") String keyword);

    @Query("SELECT s FROM Store s JOIN FETCH s.partner")
    List<Store> findAllWithPartner();

    Store findByStoreName(String storeName);

    @Query(
            value = """
                    SELECT s.storeId
                    FROM store s
                    WHERE s.location IS NOT NULL
                      AND s.partnerId = :partnerId
                    ORDER BY ST_DistanceSphere(location::geometry, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)) ASC
                    LIMIT 30
                    """,
            nativeQuery = true
    )
    List<Long> searchNearbyStoreIdsByPartnerId(
            @Param("lng") double lng,
            @Param("lat") double lat,
            @Param("partnerId") Long partnerId
    );

    @Query("""
                SELECT s FROM Store s
                JOIN FETCH s.partner p
                WHERE s.storeId = :storeId AND p.partnerId = :partnerId
            """)
    Optional<Store> findByIdAndPartnerId(
            @Param("storeId") Long storeId,
            @Param("partnerId") Long partnerId);
}
