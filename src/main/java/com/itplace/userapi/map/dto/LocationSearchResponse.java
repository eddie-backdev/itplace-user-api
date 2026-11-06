package com.itplace.userapi.map.dto;

import java.util.List;

public record LocationSearchResponse(List<Location> locations, boolean partial) {
    public record Location(
            String id,
            String name,
            String address,
            String roadAddress,
            double lat,
            double lng,
            Kind kind,
            Integer distanceMeters
    ) {
    }

    public enum Kind {
        ADDRESS, PLACE
    }
}
