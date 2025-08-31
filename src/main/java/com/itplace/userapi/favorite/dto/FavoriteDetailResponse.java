package com.itplace.userapi.favorite.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class FavoriteDetailResponse {
    private Long benefitId;
    private String benefitName;
    private String benefitDescription;
    private String benefitLimit;
    private String partnerName;
    private String partnerImage;
    private List<TierBenefitDetail> tiers;
}

