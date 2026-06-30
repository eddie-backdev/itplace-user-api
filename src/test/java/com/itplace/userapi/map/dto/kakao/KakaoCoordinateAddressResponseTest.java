package com.itplace.userapi.map.dto.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KakaoCoordinateAddressResponseTest {

    @Test
    void toRegionAddress_prefersRoadAddressRegion() {
        KakaoCoordinateAddressResponse.Address roadAddress = new KakaoCoordinateAddressResponse.Address(
                "서울 강남구 테헤란로", "서울", "강남구", "역삼동");
        KakaoCoordinateAddressResponse.Address jibunAddress = new KakaoCoordinateAddressResponse.Address(
                "서울 강남구 역삼동", "서울", "강남구", "삼성동");
        KakaoCoordinateAddressResponse response = new KakaoCoordinateAddressResponse(
                List.of(new KakaoCoordinateAddressResponse.Document(roadAddress, jibunAddress)));

        assertThat(response.toRegionAddress()).isEqualTo("서울 강남구 역삼동");
    }

    @Test
    void toRegionAddress_usesJibunAddressWhenRoadAddressIsMissing() {
        KakaoCoordinateAddressResponse.Address jibunAddress = new KakaoCoordinateAddressResponse.Address(
                "부산 남구 대연동", "부산", "남구", "대연동");
        KakaoCoordinateAddressResponse response = new KakaoCoordinateAddressResponse(
                List.of(new KakaoCoordinateAddressResponse.Document(null, jibunAddress)));

        assertThat(response.toRegionAddress()).isEqualTo("부산 남구 대연동");
    }

    @Test
    void toRegionAddress_returnsNullWhenDocumentsAreEmpty() {
        KakaoCoordinateAddressResponse response = new KakaoCoordinateAddressResponse(List.of());

        assertThat(response.toRegionAddress()).isNull();
    }
}
