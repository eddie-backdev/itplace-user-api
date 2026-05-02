package com.itplace.userapi.benefit.entity;

import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.BenefitTypeConverter;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.entity.enums.UsageTypeConverter;
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
import java.time.LocalDateTime;
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

    @Column(length = 512)
    private String sourceKey;

    @Column(length = 512)
    private String sourceUrl;

    @Column(length = 100)
    private String sourceCategory;

    private LocalDateTime lastCrawledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benefitLimit")
    private BenefitPolicy benefitPolicy;

    /** 통신사 사이트에 노출되는 원문 혜택명. 공통 Benefit 이름과 다를 수 있다. */
    @Column(length = 512)
    private String carrierBenefitName;

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
}
