package com.itplace.userapi.map.dto.response;

public record ReverseGeocodeResponse(String addressName) {
    public static ReverseGeocodeResponse currentLocation() {
        return new ReverseGeocodeResponse("현재 위치");
    }
}
