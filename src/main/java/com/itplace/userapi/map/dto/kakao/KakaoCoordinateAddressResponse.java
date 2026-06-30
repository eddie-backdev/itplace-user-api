package com.itplace.userapi.map.dto.kakao;

import java.util.List;
import java.util.stream.Stream;
import org.springframework.util.StringUtils;

public record KakaoCoordinateAddressResponse(List<Document> documents) {

    public String toRegionAddress() {
        if (documents == null || documents.isEmpty()) {
            return null;
        }

        Document document = documents.get(0);
        Address targetAddress = document.road_address() != null
                ? document.road_address()
                : document.address();

        if (targetAddress == null) {
            return null;
        }

        String addressName = Stream.of(
                        targetAddress.region_1depth_name(),
                        targetAddress.region_2depth_name(),
                        targetAddress.region_3depth_name()
                )
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + " " + right)
                .orElse(null);

        return StringUtils.hasText(addressName) ? addressName : null;
    }

    public record Document(Address road_address, Address address) {
    }

    public record Address(
            String address_name,
            String region_1depth_name,
            String region_2depth_name,
            String region_3depth_name
    ) {
    }
}
