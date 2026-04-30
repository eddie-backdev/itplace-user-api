package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
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
    private Carrier carrier;
    private Boolean active;

    // Partner Info
    private String partnerName;
    private String image;
}
