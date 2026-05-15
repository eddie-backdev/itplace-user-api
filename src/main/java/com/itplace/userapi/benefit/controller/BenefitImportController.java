package com.itplace.userapi.benefit.controller;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.dto.request.BenefitSnapshotImportRequest;
import com.itplace.userapi.benefit.dto.response.BenefitSnapshotImportResponse;
import com.itplace.userapi.benefit.service.BenefitImportService;
import com.itplace.userapi.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/internal/benefits", "/internal/benefits"})
@RequiredArgsConstructor
public class BenefitImportController {

    private static final String INTERNAL_API_KEY_HEADER = "X-Internal-Api-Key";

    private final BenefitImportService benefitImportService;

    @PostMapping({"/import", "/snapshots"})
    public ResponseEntity<ApiResponse<BenefitSnapshotImportResponse>> importBenefits(
            @RequestHeader(name = INTERNAL_API_KEY_HEADER, required = false) String apiKey,
            @RequestBody @Valid BenefitSnapshotImportRequest request
    ) {
        BenefitSnapshotImportResponse result = benefitImportService.importSnapshot(request, apiKey);
        ApiResponse<BenefitSnapshotImportResponse> body = ApiResponse.of(BenefitCode.BENEFIT_IMPORT_SUCCESS, result);
        return body.toResponseEntity();
    }
}
