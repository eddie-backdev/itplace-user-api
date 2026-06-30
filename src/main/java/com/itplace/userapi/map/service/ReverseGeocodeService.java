package com.itplace.userapi.map.service;

import com.itplace.userapi.map.client.KakaoLocalAddressClient;
import com.itplace.userapi.map.dto.response.ReverseGeocodeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReverseGeocodeService {

    private final KakaoLocalAddressClient kakaoLocalAddressClient;

    public ReverseGeocodeResponse resolveAddress(double lat, double lng) {
        return kakaoLocalAddressClient.findRegionAddress(lat, lng)
                .map(ReverseGeocodeResponse::new)
                .orElseGet(ReverseGeocodeResponse::currentLocation);
    }
}
