package com.itplace.userapi.mobile.map.service;

import com.itplace.userapi.mobile.map.dto.MobileMapNearbyResponse;

public interface MobileMapService {
    MobileMapNearbyResponse findNearby(
            double lat,
            double lng,
            double radiusMeters,
            String carrier,
            String category,
            String keyword,
            String partnerName
    );
}
