package com.itplace.userapi.map.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 지도 viewport에 표시할 지점과 제휴처 정보를 분리한 경량 응답이다.
 *
 * <p>동일 제휴처의 이미지와 혜택이 모든 지점에 반복되지 않도록 제휴처 목록을 별도로 제공한다.</p>
 */
@Getter
@Builder
public class MapStorePreviewBatchResponse {

    private List<StorePreview> stores;
    private List<PartnerPreview> partners;

    @Getter
    @Builder
    public static class StorePreview {
        private Long storeId;
        private Long partnerId;
        private String storeName;
        private Double latitude;
        private Double longitude;
        private String address;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String roadName;
        private String roadAddress;
        private String postCode;
        private Boolean hasCoupon;
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Double distance;
        /** Absent means use the partner's tiers; an empty list is an explicit override. */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<TierBenefitDto> tierBenefit;
    }

    @Getter
    @Builder
    public static class PartnerPreview {
        private Long partnerId;
        private String partnerName;
        private String category;
        private String image;
        private List<TierBenefitDto> tierBenefit;
    }
}
