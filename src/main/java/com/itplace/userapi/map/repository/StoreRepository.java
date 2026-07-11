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
                            s.latitude::double precision AS latitude,
                            s.longitude::double precision AS longitude,
                            ST_Transform(s.location::geometry, 3857) AS geom
                        FROM store s
                        JOIN partner p ON s.partnerId = p.partnerId
                        WHERE s.location IS NOT NULL
                          AND (:category IS NULL OR p.category = :category)
                          AND s.location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
                          AND s.longitude BETWEEN :minLng AND :maxLng
                          AND s.latitude BETWEEN :minLat AND :maxLat
                    ),
                    gridded_store AS (
                        SELECT
                            store_id,
                            latitude,
                            longitude,
                            ST_X(geom) AS map_x,
                            ST_Y(geom) AS map_y,
                            FLOOR(ST_X(geom) / :gridSizeMeters)::bigint AS grid_x,
                            FLOOR(ST_Y(geom) / :gridSizeMeters)::bigint AS grid_y
                        FROM filtered_store
                    ),
                    grid_summary AS (
                        SELECT
                            grid_x,
                            grid_y,
                            MIN(latitude) AS singleton_latitude,
                            MIN(longitude) AS singleton_longitude,
                            AVG(map_x) AS centroid_x,
                            AVG(map_y) AS centroid_y,
                            COUNT(*) AS store_count
                        FROM gridded_store
                        GROUP BY grid_x, grid_y
                    )
                    SELECT
                        CONCAT('g:', :mapLevel, ':', grid_x, ':', grid_y) AS "clusterId",
                        COALESCE(:category, '전체') AS "category",
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
                    FROM grid_summary
                    ORDER BY store_count DESC, grid_y ASC, grid_x ASC
                    LIMIT :clusterLimit
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
            @Param("gridSizeMeters") double gridSizeMeters,
            @Param("clusterLimit") int clusterLimit
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
