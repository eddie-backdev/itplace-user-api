package com.itplace.userapi.benefit.entity;

import com.itplace.userapi.benefit.entity.enums.Grade;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
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
@Table(name = "tierBenefit")
@IdClass(TierBenefitId.class)
public class TierBenefit {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Grade grade;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "benefitId", nullable = false)
    private Benefit benefit;

    @Column(nullable = false)
    private String context;

    private Boolean isAll;

    private Integer discountValue;
}
