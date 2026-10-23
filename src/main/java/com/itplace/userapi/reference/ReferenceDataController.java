package com.itplace.userapi.reference;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.common.BaseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reference-data")
@RequiredArgsConstructor
public class ReferenceDataController {
    private final ReferenceDataService service;

    @GetMapping
    public ResponseEntity<ApiResponse<ReferenceData>> getReferenceData() {
        return ApiResponse.of(new BaseCode() {
            @Override public String getCode() { return "REFERENCE_DATA_SUCCESS"; }
            @Override public HttpStatus getStatus() { return HttpStatus.OK; }
            @Override public String getMessage() { return "참조 데이터 조회 성공"; }
        }, service.getReferenceData()).toResponseEntity();
    }
}
