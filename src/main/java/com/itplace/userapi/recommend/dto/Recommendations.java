package com.itplace.userapi.recommend.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Recommendations {
    private int rank;
    private String partnerName;
    private String reason;
    private String imgUrl;
    private List<Long> benefitIds;
}

