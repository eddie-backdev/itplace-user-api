package com.itplace.userapi.inquiry.dto.response;

import com.itplace.userapi.inquiry.entity.Inquiry;
import java.time.LocalDateTime;

public record InquiryCreateResponse(
        Long id,
        String status,
        LocalDateTime createdAt
) {
    public static InquiryCreateResponse from(Inquiry inquiry) {
        return new InquiryCreateResponse(
                inquiry.getId(),
                inquiry.getStatus().name(),
                inquiry.getCreatedDate()
        );
    }
}
