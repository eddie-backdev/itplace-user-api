package com.itplace.userapi.favorite.dto.response;

import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse.BenefitPreview;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FavoriteResponse {
    private Long benefitId;
    private String benefitName;
    private String partnerName;
    private String partnerImage;
    private List<BenefitPreview> previews;
}
