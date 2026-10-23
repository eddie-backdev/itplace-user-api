package com.itplace.userapi.benefit.entity;

import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import com.itplace.userapi.benefit.entity.enums.BenefitTypeConverter;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.entity.enums.UsageTypeConverter;
import com.itplace.userapi.benefit.entity.enums.MembershipOptionType;
import com.itplace.userapi.benefit.entity.enums.PublicationStatus;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.service.MembershipDisplayName;
import com.itplace.userapi.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(
        name = "benefitCarrierPolicy",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_benefit_carrier_policy_source",
                columnNames = {"carrier", "sourceKey"}
        )
)
public class BenefitCarrierPolicy extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long benefitCarrierPolicyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benefitId", nullable = false)
    private Benefit benefit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Carrier carrier;

    @Builder.Default
    @Column(nullable = false)
    private Boolean active = true;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "publicationstatus", length = 16)
    private PublicationStatus publicationStatus = PublicationStatus.APPROVED;

    @Column(name = "holdreason", length = 512)
    private String holdReason;

    @Column(name = "publicationreviewedat")
    private Instant publicationReviewedAt;

    @Column(length = 512)
    private String sourceKey;

    @Column(length = 512)
    private String sourceUrl;

    @Column(length = 100)
    private String sourceCategory;

    private LocalDateTime lastCrawledAt;

    private Long sourceEvidenceId;

    private Instant sourceCapturedAt;

    @Column(length = 32600)
    private String sourceLimitText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private BenefitConditions conditions;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benefitLimit")
    private BenefitPolicy benefitPolicy;

    /** 수집된 통신사별 혜택명. 공통 Benefit 이름과 다를 수 있다. */
    @Column(length = 512)
    private String carrierBenefitName;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "membershipoptiontype", nullable = false, length = 32)
    private MembershipOptionType membershipOptionType = MembershipOptionType.UNKNOWN;

    @Column(name = "sourceoptionlabel", length = 100)
    private String sourceOptionLabel;

    @Convert(converter = BenefitTypeConverter.class)
    private BenefitType type;

    @Column(length = 32600)
    private String description;

    @Column(length = 32600)
    private String manual;

    @Convert(converter = UsageTypeConverter.class)
    private UsageType usageType;

    @Column(length = 512)
    private String url;

    @Builder.Default
    @OneToMany(mappedBy = "benefitCarrierPolicy")
    private List<CarrierTierBenefit> tierBenefits = new ArrayList<>();

    public String getDisplayBenefitLimit() {
        String sourceLimit = joinLimits(sourceLimitText, conditions == null ? List.of() : conditions.limits());
        if (sourceLimit != null) {
            return sourceLimit;
        }
        // 레거시 UNLIMITED는 횟수 제한이 없다는 해석이 아니라 확인 필요 상태로 표시한다.
        if (benefitPolicy == null || benefitPolicy.getCode() == BenefitPolicyCode.UNKNOWN
                || benefitPolicy.getCode() == BenefitPolicyCode.UNLIMITED) {
            return "이용 방법에서 횟수·한도 확인";
        }
        return benefitPolicy.getName();
    }

    private String joinLimits(String sourceLimit, List<String> limits) {
        java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
        if (sourceLimit != null && !sourceLimit.isBlank()) {
            java.util.Arrays.stream(sourceLimit.split("\\n"))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(distinct::add);
        }
        if (limits != null) limits.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).forEach(distinct::add);
        return distinct.isEmpty() ? null : String.join("\n", distinct);
    }

    public String getPublicUrl() {
        if (url != null && !url.isBlank()) {
            return url.trim();
        }
        return sourceUrl == null || sourceUrl.isBlank() ? null : sourceUrl.trim();
    }

    public String getDisplayBenefitName() {
        String partnerName = benefit == null || benefit.getPartner() == null
                ? null : benefit.getPartner().getPartnerName();
        String rawName = carrierBenefitName != null && !carrierBenefitName.isBlank()
                ? carrierBenefitName : benefit == null ? null : benefit.getBenefitName();
        return MembershipDisplayName.fullName(this, rawName, partnerName);
    }
}
