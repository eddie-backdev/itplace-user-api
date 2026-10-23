package com.itplace.userapi.benefit.entity;

import com.itplace.userapi.benefit.entity.enums.Grade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Convert;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "carrierTierBenefit")
public class CarrierTierBenefit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long carrierTierBenefitId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benefitCarrierPolicyId", nullable = false)
    private BenefitCarrierPolicy benefitCarrierPolicy;

    @Convert(converter = com.itplace.userapi.benefit.entity.enums.GradeConverter.class)
    @Column(nullable = false)
    private Grade grade;

    @Column(nullable = false, length = 32600)
    private String context;

    private Boolean isAll;

    private Integer discountValue;

    @Column(name = "offerkey", length = 512)
    private String offerKey;
}
