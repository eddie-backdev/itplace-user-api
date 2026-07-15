package com.itplace.userapi.benefit.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BenefitSnapshotImportRequest {
    @NotNull(message = "통신사는 필수 항목입니다.")
    private Carrier carrier;

    @JsonAlias("exportedAt")
    private LocalDateTime crawledAt;

    @Valid
    @NotEmpty(message = "동기화할 혜택 목록은 비어 있을 수 없습니다.")
    private List<BenefitSnapshotItem> benefits;

    @Getter
    @Setter
    public static class BenefitSnapshotItem {
        @NotBlank(message = "sourceKey는 필수 항목입니다.")
        private String sourceKey;

        private String sourceCategory;

        @NotBlank(message = "제휴사명은 필수 항목입니다.")
        private String partnerName;
        private List<String> partnerAliases = List.of();

        @JsonAlias("partnerImageUrl")
        private String partnerImage;
        private String partnerCategory;

        @NotNull(message = "메인 카테고리는 필수 항목입니다.")
        private MainCategory mainCategory;

        @NotBlank(message = "혜택명은 필수 항목입니다.")
        private String benefitName;

        @NotNull(message = "혜택 타입은 필수 항목입니다.")
        private BenefitType type;

        private String description;
        private String manual;
        private BenefitPolicyCode benefitPolicyCode = BenefitPolicyCode.UNLIMITED;

        @NotNull(message = "사용 타입은 필수 항목입니다.")
        private UsageType usageType;

        /** 크롤러가 수집한 원본 상세 페이지 URL. */
        private String sourceUrl;

        /** 사용자에게 열어줄 혜택 URL. 값이 없으면 sourceUrl을 폴백으로 사용한다. */
        private String url;
        private Boolean active;

        @Valid
        @JsonAlias("tiers")
        private List<TierBenefitSnapshot> tierBenefits = List.of();
    }

    @Getter
    @Setter
    public static class TierBenefitSnapshot {
        @NotNull(message = "등급은 필수 항목입니다.")
        private Grade grade;

        @NotBlank(message = "등급 혜택 설명은 필수 항목입니다.")
        private String context;

        private Boolean isAll;
        private Integer discountValue;
    }
}
