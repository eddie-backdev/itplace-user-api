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
                        s.business AS "business",
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
                    WITH selected_region AS MATERIALIZED (
                        SELECT
                            mapped.store_id,
                            CASE :administrativeUnitType
                                WHEN 'CITY' THEN 'CITY'
                                WHEN 'TOWN' THEN mapped.town_region_type
                                ELSE mapped.legal_dong_region_type
                            END AS region_type,
                            CASE :administrativeUnitType
                                WHEN 'CITY' THEN mapped.city_region_key
                                WHEN 'TOWN' THEN mapped.town_region_key
                                ELSE mapped.legal_dong_region_key
                            END AS region_key,
                            CASE :administrativeUnitType
                                WHEN 'CITY' THEN mapped.city_region_name
                                WHEN 'TOWN' THEN mapped.town_region_name
                                ELSE mapped.legal_dong_region_name
                            END AS region_name,
                            CASE :administrativeUnitType
                                WHEN 'CITY' THEN mapped.city_region_hash
                                WHEN 'TOWN' THEN mapped.town_region_hash
                                ELSE mapped.legal_dong_region_hash
                            END AS region_hash
                        FROM map_store_cluster_region mapped
                    ),
                    region_summary AS (
                        SELECT
                            region.region_type,
                            MIN(region.region_key) AS region_key,
                            MIN(region.region_name) AS region_name,
                            region.region_hash,
                            COUNT(*) AS store_count
                        FROM store s
                        JOIN partner p ON s.partnerId = p.partnerId
                        JOIN selected_region region ON region.store_id = s.storeId
                        WHERE s.location IS NOT NULL
                          AND (:category IS NULL OR p.category = :category)
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
                          AND s.location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
                          AND s.longitude BETWEEN CAST(:minLng AS NUMERIC) AND CAST(:maxLng AS NUMERIC)
                          AND s.latitude BETWEEN CAST(:minLat AS NUMERIC) AND CAST(:maxLat AS NUMERIC)
                          AND region.region_key IS NOT NULL
                        GROUP BY region.region_type, region.region_hash
                    )
                    SELECT
                        CONCAT(
                            'a:', :mapLevel, ':', region_summary.region_type, ':',
                            region_summary.region_hash
                        ) AS "clusterId",
                        COALESCE(:category, '전체') AS "category",
                        region_summary.region_type AS "administrativeUnitType",
                        region_summary.region_name AS "administrativeUnitName",
                        region_anchor.latitude AS "latitude",
                        region_anchor.longitude AS "longitude",
                        region_summary.store_count AS "count"
                    FROM region_summary
                    JOIN map_region_anchor region_anchor
                      ON region_anchor.region_type = region_summary.region_type
                     AND region_anchor.region_key = region_summary.region_key
                    ORDER BY
                        region_summary.store_count DESC,
                        region_summary.region_type ASC,
                        region_summary.region_hash ASC
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
            @Param("administrativeUnitType") String administrativeUnitType
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

    @Query(
            value = """
                    SELECT ranked.store_id
                    FROM (
                        SELECT
                            s.storeId AS store_id,
                            s.partnerId AS partner_id,
                            ST_DistanceSphere(
                                s.location::geometry,
                                ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
                            ) AS distance,
                            ROW_NUMBER() OVER (
                                PARTITION BY s.partnerId
                                ORDER BY ST_DistanceSphere(
                                    s.location::geometry,
                                    ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
                                )
                            ) AS row_num
                        FROM store s
                        WHERE s.location IS NOT NULL
                          AND s.partnerId IN :partnerIds
                    ) ranked
                    WHERE ranked.row_num <= 30
                    ORDER BY ranked.partner_id, ranked.distance
                    """,
            nativeQuery = true
    )
    List<Long> searchNearbyStoreIdsByPartnerIds(
            @Param("lng") double lng,
            @Param("lat") double lat,
            @Param("partnerIds") List<Long> partnerIds
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
