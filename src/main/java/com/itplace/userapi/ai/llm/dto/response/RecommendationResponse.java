package com.itplace.userapi.ai.llm.dto.response;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RecommendationResponse {
    private String reason;
    private List<PartnerSummary> partners;

    @Data
    @Builder
    public static class PartnerSummary {
        private Long partnerId;
        private Long benefitId;
        private String partnerName;
        private String benefitName;
        private String category;
        private Long representativeStoreId;
        private String representativeStoreName;
        private Integer storeCount;
        private String benefitText;
        private String imgUrl;
    }
}

