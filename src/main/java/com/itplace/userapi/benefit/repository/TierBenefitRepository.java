package com.itplace.userapi.benefit.repository;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.TierBenefit;
import com.itplace.userapi.benefit.entity.TierBenefitId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TierBenefitRepository extends JpaRepository<TierBenefit, TierBenefitId> {
    List<TierBenefit> findAllByBenefit_BenefitId(Long benefitId);

    List<TierBenefit> findAllByBenefitIn(List<Benefit> benefits);
}
