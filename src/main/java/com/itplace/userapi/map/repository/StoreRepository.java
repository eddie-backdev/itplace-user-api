package com.itplace.userapi.map.repository;

import com.itplace.userapi.map.entity.Store;
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
    List<Long> findRandomStoreIdsInBounds(
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
    List<Long> findRandomStoreIdsByCategory(
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

    @Query("SELECT s FROM Store s JOIN FETCH s.partner WHERE s.storeId IN :storeIds")
    List<Store> findAllByStoreIdInWithPartner(@Param("storeIds") List<Long> storeIds);

    @Query(
            value = """
                    SELECT s.*,
                           CASE
                               WHEN LOWER(COALESCE(s.storeName, '')) = LOWER(:keyword)
                                    OR LOWER(COALESCE(p.partnerName, '')) = LOWER(:keyword)
                               THEN 1 ELSE 0
                           END AS is_exact,
                           ST_DistanceSphere(
                               s.location::geometry,
                               ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)
                           ) AS distance
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
                        is_exact DESC,
                        distance ASC
                    LIMIT 30
                    """,
            nativeQuery = true
    )
    List<Store> searchNearbyStores(@Param("lng") double lng, @Param("lat") double lat,
                                   @Param("category") String category, @Param("keyword") String keyword);

    @Query("SELECT s FROM Store s JOIN FETCH s.partner")
    List<Store> findAllWithPartner();

    Store findByStoreName(String storeName);

    @Query(
            value = """
                    SELECT s.*
                    FROM store s
                    WHERE s.location IS NOT NULL
                      AND s.partnerId = :partnerId
                    ORDER BY ST_DistanceSphere(location::geometry, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)) ASC
                    LIMIT 30
                    """,
            nativeQuery = true
    )
    List<Store> searchNearbyStoresByPartnerId(
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
