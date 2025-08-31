package com.itplace.userapi.benefit.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenefitDetailResponse {
    private Long benefitId;
    private String benefitName;
    private String description;
    private String benefitLimit;
    private String manual;
    private String url;

    // Partner Info
    private String partnerName;
    private String image;
}
