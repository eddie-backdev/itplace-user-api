package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.util.List;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MapBenefitDetailResponse {
    private Long benefitId;
    private String benefitName;
    private String image;
    private MainCategory mainCategory;
    private String manual;
    private String url;
    private String sourceUrl;
    private Instant sourceCapturedAt;
    private BenefitConditions conditions;
    private Carrier carrier;
    private UsageType usageType;
    private List<TierBenefitInfo> tierBenefits;
    private Boolean isFavorite; // 사용자 관심 혜택 여부 추가
    private List<BenefitPolicyDetail> policies;
}
