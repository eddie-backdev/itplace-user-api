package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.MainCategory;
import java.util.List;
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
    private MainCategory mainCategory;
    private String manual;
    private String url;
    private List<TierBenefitInfo> tierBenefits;
    private Boolean isFavorite; // 사용자 관심 혜택 여부 추가
}
