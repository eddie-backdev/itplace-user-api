package com.itplace.userapi.mobile.map.dto;

import java.util.List;

public record MobileMapNearbyResponse(
        MobileMapCenterResponse center,
        double radiusMeters,
        List<MobileMapMarkerResponse> markers
) {
}
