package com.itplace.userapi.map.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class PartnerDto {
    private Long partnerId;
    private String partnerName;
    private String image;
    private String category;
}
