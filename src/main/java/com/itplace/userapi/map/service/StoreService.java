package com.itplace.userapi.map.service;

import com.itplace.userapi.map.dto.response.StoreDetailResponse;
import java.util.List;

public interface StoreService {
    List<StoreDetailResponse> findNearby(double lat, double lng, double radiusMeters, double userLat, double userLng);

    List<StoreDetailResponse> findNearbyByCategory(double lat, double lng, double radiusMeters, String category,
                                              double userLat, double userLng);

    List<StoreDetailResponse> findNearbyByKeyword(double lat, double lng, String category, String keyword, double userLat,
                                             double userLng);

    List<StoreDetailResponse> findNearbyByPartnerName(double lat, double lng, String partnerName, double userLat,
                                                 double userLng);
}
