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
                    SELECT s.storeId
                    FROM store s
                    JOIN partner p ON s.partnerId = p.partnerId
                    WHERE s.location IS NOT NULL
                      AND (:category IS NULL OR p.category = :category)
                      AND s.longitude BETWEEN :minLng AND :maxLng
                      AND s.latitude BETWEEN :minLat AND :maxLat
                      AND ST_DWithin(
                          s.location::geography,
                          ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                          :radiusMeters
                      )
                    ORDER BY ST_DistanceSphere(
                        s.location::geometry,
                        ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
                    ) ASC
                    LIMIT :limit
                    """,
            nativeQuery = true
    )
    List<Long> findStoreIdsInCellWithinRadius(
            @Param("category") String category,
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusMeters") double radiusMeters,
            @Param("minLat") double minLat,
            @Param("maxLat") double maxLat,
            @Param("minLng") double minLng,
            @Param("maxLng") double maxLng,
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
                    WITH filtered_store AS (
                        SELECT
                            s.storeId AS store_id,
                            ST_Y(s.location::geometry) AS latitude,
                            ST_X(s.location::geometry) AS longitude,
                            ST_X(ST_Transform(s.location::geometry, 3857)) AS map_x,
                            ST_Y(ST_Transform(s.location::geometry, 3857)) AS map_y,
                            ST_Transform(s.location::geometry, 3857) AS geom
                        FROM store s
                        JOIN partner p ON s.partnerId = p.partnerId
                        WHERE s.location IS NOT NULL
                          AND (:category IS NULL OR p.category = :category)
                          AND s.longitude BETWEEN :minLng AND :maxLng
                          AND s.latitude BETWEEN :minLat AND :maxLat
                    ),
                    dbscan_store AS (
                        SELECT
                            fs.store_id,
                            fs.latitude,
                            fs.longitude,
                            fs.map_x,
                            fs.map_y,
                            fs.geom,
                            ST_ClusterDBSCAN(fs.geom, :clusterEpsMeters, 2) OVER () AS density_cluster_index
                        FROM filtered_store fs
                    ),
                    density_clustered_store AS (
                        SELECT
                            store_id,
                            latitude,
                            longitude,
                            map_x,
                            map_y,
                            geom,
                            CASE
                                WHEN density_cluster_index IS NULL THEN CONCAT('s:', store_id)
                                ELSE CONCAT('d:', density_cluster_index)
                            END AS density_cluster_id
                        FROM dbscan_store
                    ),
                    density_cluster_summary AS (
                        SELECT
                            density_cluster_id,
                            COUNT(*) AS store_count,
                            GREATEST(
                                1,
                                LEAST(
                                    :maxClusterSplits,
                                    CEIL(COUNT(*)::numeric / :maxClusterStoreCount)::integer
                                )
                            ) AS split_count
                        FROM density_clustered_store
                        GROUP BY density_cluster_id
                    ),
                    clustered_store AS (
                        SELECT
                            dcs.store_id,
                            dcs.latitude,
                            dcs.longitude,
                            dcs.map_x,
                            dcs.map_y,
                            CASE
                                WHEN summary.split_count <= 1 THEN dcs.density_cluster_id
                                ELSE CONCAT(
                                    dcs.density_cluster_id,
                                    ':',
                                    ST_ClusterKMeans(dcs.geom, summary.split_count)
                                        OVER (PARTITION BY dcs.density_cluster_id)
                                )
                            END AS cluster_id
                        FROM density_clustered_store dcs
                        JOIN density_cluster_summary summary
                          ON summary.density_cluster_id = dcs.density_cluster_id
                    ),
                    cluster_summary AS (
                        SELECT
                            cluster_id,
                            AVG(map_x) AS centroid_x,
                            AVG(map_y) AS centroid_y,
                            COUNT(*) AS store_count
                        FROM clustered_store
                        GROUP BY cluster_id
                    ),
                    representative_store AS (
                        SELECT
                            cs.cluster_id,
                            cs.latitude,
                            cs.longitude,
                            cs.map_x,
                            cs.map_y,
                            ROW_NUMBER() OVER (
                                PARTITION BY cs.cluster_id
                                ORDER BY
                                    POWER(cs.map_x - summary.centroid_x, 2)
                                    + POWER(cs.map_y - summary.centroid_y, 2),
                                    cs.store_id
                            ) AS row_number
                        FROM clustered_store cs
                        JOIN cluster_summary summary
                          ON summary.cluster_id = cs.cluster_id
                    )
                    SELECT
                        summary.cluster_id AS "clusterId",
                        COALESCE(:category, '전체') AS "category",
                        representative.latitude AS "latitude",
                        representative.longitude AS "longitude",
                        summary.store_count AS "count"
                    FROM cluster_summary summary
                    JOIN representative_store representative
                      ON representative.cluster_id = summary.cluster_id
                     AND representative.row_number = 1
                    ORDER BY summary.store_count DESC, summary.cluster_id ASC
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
            @Param("clusterEpsMeters") double clusterEpsMeters,
            @Param("maxClusterStoreCount") int maxClusterStoreCount,
            @Param("maxClusterSplits") int maxClusterSplits,
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
