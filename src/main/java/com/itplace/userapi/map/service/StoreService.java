package com.itplace.userapi.map.service;

import com.itplace.userapi.map.dto.response.MapStorePreviewResponse;
import com.itplace.userapi.map.dto.response.MapStoreClusterResponse;
import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import java.util.List;

public interface StoreService {
    List<MapStoreClusterResponse> findStoreClustersInView(double minLat, double minLng, double maxLat, double maxLng,
                                                          String category, int mapLevel);

    List<MapStorePreviewResponse> findStoresInViewPreviews(double minLat, double minLng, double maxLat, double maxLng,
                                                           String category, double userLat, double userLng, int limit,
                                                           boolean includeBenefits);

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
}
