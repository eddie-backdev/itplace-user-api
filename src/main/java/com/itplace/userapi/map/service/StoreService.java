package com.itplace.userapi.map.service;

import com.itplace.userapi.map.dto.StoreDetailDto;
import java.util.List;

public interface StoreService {
    List<StoreDetailDto> findNearby(double lat, double lng, double radiusMeters, double userLat, double userLng);

    List<StoreDetailDto> findNearbyByCategory(double lat, double lng, double radiusMeters, String category,
                                              double userLat, double userLng);

    List<StoreDetailDto> findNearbyByKeyword(double lat, double lng, String category, String keyword, double userLat,
                                             double userLng);

    List<StoreDetailDto> findNearbyByPartnerName(double lat, double lng, String partnerName, double userLat,
                                                 double userLng);
}
