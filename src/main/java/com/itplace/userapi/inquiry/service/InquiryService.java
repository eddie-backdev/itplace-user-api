package com.itplace.userapi.inquiry.service;

import com.itplace.userapi.inquiry.dto.request.InquiryCreateRequest;
import com.itplace.userapi.inquiry.dto.response.InquiryCreateResponse;

public interface InquiryService {
    InquiryCreateResponse createInquiry(InquiryCreateRequest request);
}
