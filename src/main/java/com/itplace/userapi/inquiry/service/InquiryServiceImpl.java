package com.itplace.userapi.inquiry.service;

import com.itplace.userapi.inquiry.dto.request.InquiryCreateRequest;
import com.itplace.userapi.inquiry.dto.response.InquiryCreateResponse;
import com.itplace.userapi.inquiry.entity.Inquiry;
import com.itplace.userapi.inquiry.repository.InquiryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InquiryServiceImpl implements InquiryService {

    private final InquiryRepository inquiryRepository;

    @Override
    @Transactional
    public InquiryCreateResponse createInquiry(InquiryCreateRequest request) {
        Inquiry inquiry = inquiryRepository.save(
                Inquiry.builder()
                        .category(normalize(request.getCategory()))
                        .title(normalize(request.getTitle()))
                        .content(normalize(request.getContent()))
                        .build()
        );

        return InquiryCreateResponse.from(inquiry);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

}
