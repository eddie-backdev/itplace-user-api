package com.itplace.userapi.benefit.controller;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.response.BenefitDetailResponse;
import com.itplace.userapi.benefit.dto.response.BenefitListResponse;
import com.itplace.userapi.benefit.dto.response.MapBenefitDetailResponse;
import com.itplace.userapi.common.PageResult;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.service.BenefitService;
import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Benefit", description = "제휴사 혜택 정보 조회 관련 API")
@RequestMapping({"/api/v1/benefits", "/api/v1/benefit"})
@RestController
@RequiredArgsConstructor
public class BenefitController {
    private final BenefitService benefitService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<BenefitListResponse>>> getBenefits(
            @RequestParam(required = false) MainCategory mainCategory,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) UsageType filter,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Carrier carrier,
            @RequestParam(required = false) List<String> carriers,
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Long userId = (principalDetails != null) ? principalDetails.getUserId() : null;
        PageResult<BenefitListResponse> result = benefitService.getBenefitList(
                mainCategory, category, filter, sort, keyword, resolveCarriers(carrier, carriers), userId, pageable
        );
        ApiResponse<PageResult<BenefitListResponse>> body = ApiResponse.of(BenefitCode.BENEFIT_LIST_SUCCESS, result);
        return body.toResponseEntity();
    }

    private List<Carrier> resolveCarriers(Carrier carrier, List<String> carriers) {
        if (carriers == null || carriers.isEmpty()) {
            return carrier == null ? List.of() : List.of(carrier);
        }

        try {
            List<Carrier> parsedCarriers = carriers.stream()
                    .flatMap(value -> Arrays.stream(value.split(",")))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(Carrier::valueOf)
                    .distinct()
                    .toList();

            if (parsedCarriers.isEmpty()) {
                return carrier == null ? List.of() : List.of(carrier);
            }
            return parsedCarriers;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 통신사 필터입니다.", exception);
        }
    }

    @GetMapping("/{benefitId}")
    public ResponseEntity<ApiResponse<BenefitDetailResponse>> getBenefitDetail(@PathVariable Long benefitId) {
        BenefitDetailResponse result = benefitService.getBenefitDetail(benefitId);
        ApiResponse<BenefitDetailResponse> body = ApiResponse.of(BenefitCode.BENEFIT_DETAIL_SUCCESS, result);
        return body.toResponseEntity();
    }

    @GetMapping("/map-detail")
    public ResponseEntity<ApiResponse<MapBenefitDetailResponse>> getMapBenefitDetail(
            @RequestParam Long storeId,
            @RequestParam Long partnerId,
            @RequestParam(required = false) MainCategory mainCategory,
            @RequestParam(required = false) Carrier carrier,
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        Long userId = (principalDetails != null) ? principalDetails.getUserId() : null;
        MapBenefitDetailResponse detail = benefitService.getMapBenefitDetail(
                storeId, partnerId, mainCategory, carrier, userId);
        ApiResponse<MapBenefitDetailResponse> body = ApiResponse.of(BenefitCode.BENEFIT_DETAIL_SUCCESS, detail);
        return body.toResponseEntity();
    }
}
