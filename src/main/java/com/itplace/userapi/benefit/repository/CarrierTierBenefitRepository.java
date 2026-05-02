package com.itplace.userapi.benefit.repository;

import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CarrierTierBenefitRepository extends JpaRepository<CarrierTierBenefit, Long> {

    List<CarrierTierBenefit> findAllByBenefitCarrierPolicy(BenefitCarrierPolicy benefitCarrierPolicy);

    List<CarrierTierBenefit> findAllByBenefitCarrierPolicyIn(List<BenefitCarrierPolicy> benefitCarrierPolicies);
}
