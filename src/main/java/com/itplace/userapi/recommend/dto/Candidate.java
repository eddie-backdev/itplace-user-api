package com.itplace.userapi.recommend.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Candidate {
    private Long partnerId;
    private Long benefitId;
    private String benefitName;
    private String partnerName;
    private String category;
    private String description;
    private String context;
}
