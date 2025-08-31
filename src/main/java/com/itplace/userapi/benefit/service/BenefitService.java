package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.dto.response.BenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.BenefitListResponse;
import com.itplace.userapi.benefit.dto.response.MapBenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.PagedResponse;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import org.springframework.data.domain.Pageable;

public interface BenefitService {
    PagedResponse<BenefitListResponse> getBenefitList(
            MainCategory mainCategory,
            String category,
            UsageType filter,
            String keyword,
            Long userId,
            Pageable pageable
    );

    BenefitDetailResponse getBenefitDetail(Long benefitId);

    MapBenefitDetailResponse getMapBenefitDetail(Long storeId, Long partnerId, MainCategory mainCategory, Long userId);
}
