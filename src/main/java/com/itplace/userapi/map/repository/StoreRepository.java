package com.itplace.userapi.map.repository;

import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.repository.projection.StoreClusterProjection;
import io.lettuce.core.dynamic.annotation.Param;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    @Transactional(readOnly = true, timeout = 5)
    @Query(
            value = """
                    SELECT s.storeId FROM store s
                    WHERE s.active = true
                    AND s.partnerId = ANY (ARRAY(
                        SELECT DISTINCT b.partnerId
                        FROM benefit b
                        JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                        WHERE b.active = true
                          AND bcp.active = true
                          AND bcp.usageType IN ('offline', 'both')
                    ))
                    AND ST_DWithin(
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

    @Transactional(readOnly = true, timeout = 5)
    @Query(
            value = """
                    SELECT s.storeId
                    FROM store s
                    WHERE s.active = true
                    AND s.partnerId = ANY (ARRAY(
                        SELECT DISTINCT b.partnerId
                        FROM benefit b
                        JOIN partner p ON p.partnerId = b.partnerId
                        JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                        WHERE p.category = :category
                          AND b.active = true
                          AND bcp.active = true
                          AND bcp.usageType IN ('offline', 'both')
                    ))
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

    @Transactional(readOnly = true, timeout = 5)
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
                        WHERE s.location IS NOT NULL
                          AND s.active = true
                          AND s.partnerId = ANY (ARRAY(
                              SELECT DISTINCT b.partnerId
                              FROM benefit b
                              JOIN partner p ON p.partnerId = b.partnerId
                              JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                              WHERE (:category IS NULL OR p.category = :category)
                                AND b.active = true
                                AND bcp.active = true
                                AND bcp.usageType IN ('offline', 'both')
                          ))
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

    @Transactional(readOnly = true, timeout = 5)
    @Query(
            value = """
                    -- 숫자 좌표와 geometry bbox의 중복 selectivity가 제휴사 조인을 왜곡하지 않게 분리한다.
                    WITH visible_store AS MATERIALIZED (
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
                            s.hasCoupon AS "hasCoupon",
                            s.latitude::double precision AS numeric_latitude,
                            s.longitude::double precision AS numeric_longitude
                        FROM store s
                        JOIN partner p ON s.partnerId = p.partnerId
                        WHERE s.location IS NOT NULL
                          AND s.active = true
                          -- 혜택 제휴사를 한 번만 계산해 매장마다 정책을 다시 조인하지 않는다.
                          AND s.partnerId = ANY (ARRAY(
                              SELECT DISTINCT b.partnerId
                              FROM benefit b
                              JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                              WHERE b.active = true
                                AND bcp.active = true
                                AND bcp.usageType IN ('offline', 'both')
                          ))
                          AND (:category IS NULL OR p.category = :category)
                          AND map_store_partner_matches(s.mapNormalizedName, s.mapNormalizedBusiness, p.mapNormalizedName)
                          AND s.location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
                    )
                    SELECT "storeId", "partnerId", "storeName", "business", "partnerName", "category",
                           "image", "latitude", "longitude", "address", "roadName", "roadAddress",
                           "postCode", "hasCoupon"
                    FROM visible_store
                    WHERE numeric_longitude BETWEEN :minLng AND :maxLng
                      AND numeric_latitude BETWEEN :minLat AND :maxLat
                    ORDER BY ABS(numeric_latitude - :centerLat) + ABS(numeric_longitude - :centerLng), "storeId"
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<Object[]> findStorePreviewsInView(
            @Param("minLat") double minLat,
            @Param("maxLat") double maxLat,
            @Param("minLng") double minLng,
            @Param("maxLng") double maxLng,
            @Param("centerLat") double centerLat,
            @Param("centerLng") double centerLng,
            @Param("category") String category,
            @Param("limit") int limit
    );



    @Transactional(readOnly = true, timeout = 5)
    @Query(
            value = """
                    WITH summarized_region AS MATERIALIZED (
                        SELECT
                            summary.region_type,
                            summary.region_hash,
                            summary.region_key,
                            MIN(summary.region_name) AS region_name,
                            region_anchor.latitude,
                            region_anchor.longitude,
                            CAST(SUM(summary.store_count) AS BIGINT) AS store_count
                        FROM map_region_store_summary summary
                        JOIN map_region_anchor region_anchor
                          ON region_anchor.region_type = summary.region_type
                         AND region_anchor.region_key = summary.region_key
                        WHERE summary.aggregation_unit = :administrativeUnitType
                          AND (:category IS NULL OR summary.category = :category)
                          AND region_anchor.longitude BETWEEN :minLng AND :maxLng
                          AND region_anchor.latitude BETWEEN :minLat AND :maxLat
                        GROUP BY
                            summary.region_type,
                            summary.region_hash,
                            summary.region_key,
                            region_anchor.latitude,
                            region_anchor.longitude
                    )
                    SELECT
                        CONCAT(
                            'a:', :mapLevel, ':', summarized.region_type, ':',
                            summarized.region_hash
                        ) AS "clusterId",
                        COALESCE(:category, '전체') AS "category",
                        summarized.region_type AS "administrativeUnitType",
                        summarized.region_name AS "administrativeUnitName",
                        summarized.latitude AS "latitude",
                        summarized.longitude AS "longitude",
                        summarized.store_count AS "count"
                    FROM summarized_region summarized
                    ORDER BY
                        summarized.store_count DESC,
                        summarized.region_type ASC,
                        summarized.region_hash ASC
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


    @Transactional(readOnly = true, timeout = 5)
    @Query("""
            SELECT DISTINCT s
            FROM Store s
            JOIN FETCH s.partner p
            WHERE s.storeId IN :storeIds
              AND s.active = true
              AND EXISTS (
                  SELECT b.benefitId
                  FROM Benefit b
                  JOIN b.carrierPolicies policy
                  WHERE b.partner = p
                    AND b.active = true
                    AND policy.active = true
                    AND policy.usageType IN (
                        com.itplace.userapi.benefit.entity.enums.UsageType.OFFLINE,
                        com.itplace.userapi.benefit.entity.enums.UsageType.BOTH
                    )
              )
            """)
    List<Store> findAllByStoreIdInWithPartner(@Param("storeIds") List<Long> storeIds);

    @Transactional(readOnly = true, timeout = 5)
    @Query(
            value = """
                    WITH keyword_stores AS MATERIALIZED (
                        SELECT s.storeId, s.partnerId, s.storeName, s.location
                        FROM store s
                        WHERE s.active = true
                          AND s.location IS NOT NULL
                          AND (
                              LOWER(COALESCE(s.business, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(COALESCE(s.storeName, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                          )
                        UNION
                        SELECT s.storeId, s.partnerId, s.storeName, s.location
                        FROM store s
                        WHERE s.active = true
                          AND s.location IS NOT NULL
                          AND s.partnerId = ANY (ARRAY(
                              SELECT p.partnerId FROM partner p
                              WHERE LOWER(COALESCE(p.partnerName, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(COALESCE(p.category, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                          ))
                    )

                    SELECT s.storeId
                    FROM keyword_stores s
                    JOIN partner p ON s.partnerId = p.partnerId
                    WHERE s.partnerId = ANY (ARRAY(
                          SELECT DISTINCT b.partnerId
                          FROM benefit b
                          JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                          WHERE b.active = true
                            AND bcp.active = true
                            AND bcp.usageType IN ('offline', 'both')
                      ))
                    AND (:category IS NULL OR p.category = :category)
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

    @Transactional(readOnly = true, timeout = 5)
    @Query("""
            SELECT DISTINCT s
            FROM Store s
            JOIN FETCH s.partner p
            WHERE s.active = true
              AND EXISTS (
                  SELECT b.benefitId
                  FROM Benefit b
                  JOIN b.carrierPolicies policy
                  WHERE b.partner = p
                    AND b.active = true
                    AND policy.active = true
                    AND policy.usageType IN (
                        com.itplace.userapi.benefit.entity.enums.UsageType.OFFLINE,
                        com.itplace.userapi.benefit.entity.enums.UsageType.BOTH
                    )
              )
            """)
    List<Store> findAllWithPartner();

    @Transactional(readOnly = true, timeout = 5)
    @Query(
            value = """
                    SELECT s.storeId
                    FROM store s
                    WHERE s.location IS NOT NULL
                      AND s.partnerId = :partnerId
                      AND s.active = true
                      AND s.partnerId = ANY (ARRAY(
                          SELECT DISTINCT b.partnerId
                          FROM benefit b
                          JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                          WHERE b.active = true
                            AND bcp.active = true
                            AND bcp.usageType IN ('offline', 'both')
                      ))
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

    @Transactional(readOnly = true, timeout = 5)
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
                          AND s.active = true
                          AND s.partnerId = ANY (ARRAY(
                              SELECT DISTINCT b.partnerId
                              FROM benefit b
                              JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                              WHERE b.active = true
                                AND bcp.active = true
                                AND bcp.usageType IN ('offline', 'both')
                          ))
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

    @Transactional(readOnly = true, timeout = 5)
    @Query("""
                SELECT s FROM Store s
                JOIN FETCH s.partner p
                WHERE s.storeId = :storeId
                  AND p.partnerId = :partnerId
                  AND s.active = true
                  AND EXISTS (
                      SELECT b.benefitId
                      FROM Benefit b
                      JOIN b.carrierPolicies policy
                      WHERE b.partner = p
                        AND b.active = true
                        AND policy.active = true
                        AND policy.usageType IN (
                            com.itplace.userapi.benefit.entity.enums.UsageType.OFFLINE,
                            com.itplace.userapi.benefit.entity.enums.UsageType.BOTH
                        )
                  )
            """)
    Optional<Store> findByIdAndPartnerId(
            @Param("storeId") Long storeId,
            @Param("partnerId") Long partnerId);

    // Web previews: current eligibility is checked in SQL before limiting candidates or loading ES IDs.
    @Transactional(readOnly = true, timeout = 5)
    @Query(
            value = """
                    SELECT s.storeId
                    FROM store s
                    JOIN partner eligible_partner ON eligible_partner.partnerId = s.partnerId
                    WHERE s.active = true
                    AND s.partnerId = ANY (ARRAY(
                        SELECT DISTINCT b.partnerId
                        FROM benefit b
                        JOIN partner p ON p.partnerId = b.partnerId
                        JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                        WHERE (:category IS NULL OR p.category = :category)
                          AND b.active = true
                          AND bcp.active = true
                          AND bcp.usageType IN ('offline', 'both')
                    ))
                    AND ST_DWithin(
                        s.location::geography,
                        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                        :radiusMeters
                    )
                    AND map_store_partner_matches(s.mapNormalizedName, s.mapNormalizedBusiness, eligible_partner.mapNormalizedName)
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<Long> findEligibleStoreIdsWithinRadius(
            @Param("category") String category,
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("limit") int limit
    );

    @Transactional(readOnly = true, timeout = 5)
    @Query(
            value = """
                    WITH keyword_stores AS NOT MATERIALIZED (
                        SELECT s.storeId, s.partnerId, s.storeName, s.location, s.mapNormalizedName, s.mapNormalizedBusiness
                        FROM store s
                        WHERE s.active = true
                          AND s.location IS NOT NULL
                          AND (
                              LOWER(COALESCE(s.business, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(COALESCE(s.storeName, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                          )
                        UNION ALL
                        SELECT s.storeId, s.partnerId, s.storeName, s.location, s.mapNormalizedName, s.mapNormalizedBusiness
                        FROM store s
                        WHERE s.active = true
                          AND s.location IS NOT NULL
                          -- 두 분기를 겹치지 않게 만들어 넓은 문자열 행의 UNION 중복 제거/디스크 임시 쓰기를 없앤다.
                          AND (
                              LOWER(COALESCE(s.business, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(COALESCE(s.storeName, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                          ) IS NOT TRUE
                          AND s.partnerId = ANY (ARRAY(
                              SELECT p.partnerId FROM partner p
                              WHERE LOWER(COALESCE(p.partnerName, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(COALESCE(p.category, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                          ))
                    )

                    SELECT s.storeId
                    FROM keyword_stores s
                    JOIN partner p ON s.partnerId = p.partnerId
                    WHERE s.partnerId = ANY (ARRAY(
                          SELECT DISTINCT b.partnerId
                          FROM benefit b
                          JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                          WHERE b.active = true
                            AND bcp.active = true
                            AND bcp.usageType IN ('offline', 'both')
                      ))
                    AND (:category IS NULL OR p.category = :category)
                    AND map_store_partner_matches(s.mapNormalizedName, s.mapNormalizedBusiness, p.mapNormalizedName)
                    ORDER BY
                           CASE
                               WHEN LOWER(COALESCE(s.storeName, '')) = LOWER(:keyword)
                                    OR LOWER(COALESCE(p.partnerName, '')) = LOWER(:keyword)
                               THEN 1 ELSE 0
                           END DESC,
                           ST_DistanceSphere(
                               s.location::geometry,
                               ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
                           ) ASC,
                           s.storeId ASC
                    LIMIT 30
                    """,
            nativeQuery = true
    )
    List<Long> searchEligibleNearbyStoreIds(@Param("lng") double lng, @Param("lat") double lat,
                                    @Param("category") String category, @Param("keyword") String keyword);

    @Transactional(readOnly = true, timeout = 5)
    @Query("""
            SELECT DISTINCT s
            FROM Store s
            JOIN FETCH s.partner p
            WHERE s.storeId IN :storeIds
              AND s.active = true
              AND function('map_store_partner_matches', s.mapNormalizedName, s.mapNormalizedBusiness, p.mapNormalizedName) = true
              AND EXISTS (
                  SELECT b.benefitId
                  FROM Benefit b
                  JOIN b.carrierPolicies policy
                  WHERE b.partner = p
                    AND b.active = true
                    AND policy.active = true
                    AND policy.usageType IN (
                        com.itplace.userapi.benefit.entity.enums.UsageType.OFFLINE,
                        com.itplace.userapi.benefit.entity.enums.UsageType.BOTH
                    )
              )
            """)
    List<Store> findEligibleByStoreIdInWithPartner(@Param("storeIds") List<Long> storeIds);

}
