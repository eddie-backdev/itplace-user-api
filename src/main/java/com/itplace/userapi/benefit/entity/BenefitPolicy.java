package com.itplace.userapi.benefit.entity;

import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
@Table(name = "benefitPolicy")
public class BenefitPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long benefitPolicyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BenefitPolicyCode code;

    @Column(nullable = false)
    private String name;

}
