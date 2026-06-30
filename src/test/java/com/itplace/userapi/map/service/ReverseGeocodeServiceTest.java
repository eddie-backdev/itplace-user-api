package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.client.KakaoLocalAddressClient;
import com.itplace.userapi.map.dto.response.ReverseGeocodeResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReverseGeocodeServiceTest {

    @Mock
    private KakaoLocalAddressClient kakaoLocalAddressClient;

    @InjectMocks
    private ReverseGeocodeService reverseGeocodeService;

    @Test
    void resolveAddress_returnsKakaoRegionAddress() {
        when(kakaoLocalAddressClient.findRegionAddress(37.5, 127.0))
                .thenReturn(Optional.of("서울 강남구 역삼동"));

        ReverseGeocodeResponse response = reverseGeocodeService.resolveAddress(37.5, 127.0);

        assertThat(response.addressName()).isEqualTo("서울 강남구 역삼동");
    }

    @Test
    void resolveAddress_fallsBackToCurrentLocationWhenKakaoAddressIsMissing() {
        when(kakaoLocalAddressClient.findRegionAddress(37.5, 127.0))
                .thenReturn(Optional.empty());

        ReverseGeocodeResponse response = reverseGeocodeService.resolveAddress(37.5, 127.0);

        assertThat(response.addressName()).isEqualTo("현재 위치");
    }
}
