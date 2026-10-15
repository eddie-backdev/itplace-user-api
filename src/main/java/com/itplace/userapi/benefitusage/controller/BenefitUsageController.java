package com.itplace.userapi.benefitusage.controller;

import com.itplace.userapi.benefitusage.BenefitUsageCode;
import com.itplace.userapi.benefitusage.dto.request.CreateBenefitUsageRecordRequest;
import com.itplace.userapi.benefitusage.dto.request.UpdateBenefitUsageRequest;
import com.itplace.userapi.benefitusage.dto.response.BenefitUsageMonthResponse;
import com.itplace.userapi.benefitusage.dto.response.BenefitUsageRecordResponse;
import com.itplace.userapi.benefitusage.service.BenefitUsageService;
import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/benefit-usages")
public class BenefitUsageController {
    private final BenefitUsageService benefitUsageService;

    @GetMapping
    public ResponseEntity<ApiResponse<BenefitUsageMonthResponse>> getMonth(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @RequestParam String month
    ) {
        ApiResponse<BenefitUsageMonthResponse> body = ApiResponse.of(
                BenefitUsageCode.LIST_SUCCESS,
                benefitUsageService.getMonth(principalDetails.getUserId(), month));
        return body.toResponseEntity();
    }

    @PostMapping
    public ResponseEntity<ApiResponse<BenefitUsageRecordResponse>> create(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @Valid @RequestBody CreateBenefitUsageRecordRequest request
    ) {
        ApiResponse<BenefitUsageRecordResponse> body = ApiResponse.of(
                BenefitUsageCode.CREATE_SUCCESS,
                benefitUsageService.create(principalDetails.getUserId(), request));
        return body.toResponseEntity();
    }

    @PutMapping("/{recordId}")
    public ResponseEntity<ApiResponse<BenefitUsageRecordResponse>> update(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @PathVariable Long recordId,
            @Valid @RequestBody UpdateBenefitUsageRequest request
    ) {
        ApiResponse<BenefitUsageRecordResponse> body = ApiResponse.of(
                BenefitUsageCode.UPDATE_SUCCESS,
                benefitUsageService.update(principalDetails.getUserId(), recordId, request));
        return body.toResponseEntity();
    }

    @DeleteMapping("/{recordId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @PathVariable Long recordId
    ) {
        benefitUsageService.delete(principalDetails.getUserId(), recordId);
        return ApiResponse.<Void>ok(BenefitUsageCode.DELETE_SUCCESS).toResponseEntity();
    }
}
