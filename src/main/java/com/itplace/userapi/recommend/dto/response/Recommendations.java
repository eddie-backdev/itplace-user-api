package com.itplace.userapi.recommend.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Map;
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
    private String requestId;
    private String impressionId;
    private String candidateSource;
    private String algorithmVersion;
    @JsonIgnore
    private Map<String, Double> scoreComponents;
    private List<String> fallbackFlags;
}
