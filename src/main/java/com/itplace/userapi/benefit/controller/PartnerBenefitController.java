package com.itplace.userapi.benefit.controller;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.service.PartnerBenefitBrowseService;
import com.itplace.userapi.benefit.support.CarrierFilterResolver;
import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.common.PageResult;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Benefit", description = "제휴사 혜택 정보 조회 관련 API")
@RestController
@RequestMapping("/api/v1/benefits/partners")
@RequiredArgsConstructor
public class PartnerBenefitController {

    private final PartnerBenefitBrowseService partnerBenefitBrowseService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<PartnerBenefitListResponse>>> getPartners(
            @RequestParam(required = false) MainCategory mainCategory,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) UsageType filter,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Carrier carrier,
            @RequestParam(required = false) List<String> carriers,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(100) int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        PageResult<PartnerBenefitListResponse> result = partnerBenefitBrowseService.getPartners(
                mainCategory,
                category,
                filter,
                sort,
                keyword,
                CarrierFilterResolver.resolve(carrier, carriers),
                pageable
        );
        return ApiResponse.of(BenefitCode.BENEFIT_LIST_SUCCESS, result).toResponseEntity();
    }

    @GetMapping("/{partnerId}")
    public ResponseEntity<ApiResponse<PartnerBenefitDetailResponse>> getPartnerDetail(
            @PathVariable Long partnerId,
            @RequestParam(defaultValue = "BASIC_BENEFIT") MainCategory mainCategory,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        Long userId = principalDetails == null ? null : principalDetails.getUserId();
        PartnerBenefitDetailResponse result = partnerBenefitBrowseService.getPartnerDetail(
                partnerId, mainCategory, userId
        );
        return ApiResponse.of(BenefitCode.BENEFIT_DETAIL_SUCCESS, result).toResponseEntity();
    }

}
