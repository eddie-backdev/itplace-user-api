package com.itplace.userapi.map.service;

import com.itplace.userapi.map.dto.response.MapStorePreviewResponse;
import com.itplace.userapi.map.dto.response.MapStorePreviewBatchResponse;
import com.itplace.userapi.map.dto.response.MapStorePreviewPageResponse;
import com.itplace.userapi.map.dto.response.MapStoreClusterResponse;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import com.itplace.userapi.map.dto.response.StoreSeoResponse;
import java.util.List;
import java.util.Optional;

public interface StoreService {
    Optional<StoreSeoResponse> findActiveStoreSeoDetail(long storeId);
    List<StoreDetailResponse> findNearbyForMobile(double lat, double lng, double userLat, double userLng,
                                                  double radiusMeters, String carrier, String category,
                                                  String keyword, String partnerName);

    List<MapStoreClusterResponse> findStoreClustersInView(double minLat, double minLng, double maxLat, double maxLng,
                                                          String category, int mapLevel);

    List<MapStorePreviewResponse> findStoresInViewPreviews(double minLat, double minLng, double maxLat, double maxLng,
                                                           String category, double userLat, double userLng, int limit,
                                                           boolean includeBenefits);

    MapStorePreviewBatchResponse findStoresInViewPreviewBatch(double minLat, double minLng, double maxLat,
                                                              double maxLng, String category, int limit);

    MapStorePreviewBatchResponse findNearbyPreviewBatch(double lat, double lng, double radiusMeters,
                                                       String category, double userLat, double userLng);

    MapStorePreviewPageResponse findStoresInViewPreviewPage(double minLat, double minLng, double maxLat, double maxLng,
                                                            String category, int pageSize, long afterStoreId);

    MapStorePreviewPageResponse findNearbyPreviewPage(double lat, double lng, double radiusMeters, String category,
                                                       double userLat, double userLng, int pageSize, long afterStoreId);

    MapStorePreviewBatchResponse findNearbyByKeywordPreviewBatch(double lat, double lng, String category,
                                                                 String keyword, double userLat, double userLng);

    List<MapStorePreviewResponse> findNearbyPreviews(double lat, double lng, double radiusMeters, double userLat, double userLng);

    List<StoreDetailResponse> findNearby(double lat, double lng, double radiusMeters, double userLat, double userLng);

    List<StoreDetailResponse> findNearbyByCategory(double lat, double lng, double radiusMeters, String category,
                                              double userLat, double userLng);

    List<MapStorePreviewResponse> findNearbyByCategoryPreviews(double lat, double lng, double radiusMeters, String category,
                                                               double userLat, double userLng);

    List<StoreDetailResponse> findNearbyDistributedForMap(double lat, double lng, double radiusMeters, String category,
                                                           double userLat, double userLng);


    List<StoreDetailResponse> findNearbyByKeyword(double lat, double lng, String category, String keyword, double userLat,
                                             double userLng);

    List<MapStorePreviewResponse> findNearbyByKeywordPreviews(double lat, double lng, String category, String keyword,
                                                              double userLat, double userLng);

    List<StoreDetailResponse> findNearbyByPartnerName(double lat, double lng, String partnerName, double userLat,
                                                 double userLng);

    List<StoreDetailResponse> findNearbyByBenefitCandidate(double lat, double lng, Long partnerId, String partnerName,
                                                           String category, Long benefitId, double userLat,
                                                           double userLng);
}
