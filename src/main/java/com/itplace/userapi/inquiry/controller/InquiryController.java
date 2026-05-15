package com.itplace.userapi.inquiry.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.inquiry.InquiryCode;
import com.itplace.userapi.inquiry.dto.request.InquiryCreateRequest;
import com.itplace.userapi.inquiry.dto.response.InquiryCreateResponse;
import com.itplace.userapi.inquiry.service.InquiryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inquiries")
@RequiredArgsConstructor
public class InquiryController {

    private final InquiryService inquiryService;

    @PostMapping
    public ResponseEntity<ApiResponse<InquiryCreateResponse>> createInquiry(
            @Valid @RequestBody InquiryCreateRequest request
    ) {
        InquiryCreateResponse response = inquiryService.createInquiry(request);
        ApiResponse<InquiryCreateResponse> body = ApiResponse.of(InquiryCode.INQUIRY_CREATE_SUCCESS, response);
        return body.toResponseEntity();
    }
}
