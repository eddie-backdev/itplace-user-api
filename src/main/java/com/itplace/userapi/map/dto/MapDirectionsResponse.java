package com.itplace.userapi.map.dto;

import java.util.List;

public record MapDirectionsResponse(
        MapDirectionsRequest.Mode mode,
        Coordinate origin,
        Destination destination,
        List<Route> routes
) {
    public record Coordinate(double lat, double lng) {
    }

    public record Destination(Long storeId, String name, double lat, double lng) {
    }

    public record Route(
            String id,
            int totalDistanceMeters,
            int totalDurationSeconds,
            Integer tollFareWon,
            List<Segment> segments
    ) {
    }

    public record Segment(
            SegmentMode mode,
            String label,
            Integer distanceMeters,
            Integer durationSeconds,
            List<Coordinate> geometry
    ) {
    }

    public enum SegmentMode {
        WALK, BUS, SUBWAY, CAR, OTHER
    }
}
