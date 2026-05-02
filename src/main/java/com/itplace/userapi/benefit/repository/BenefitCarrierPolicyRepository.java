package com.itplace.userapi.benefit.repository;

import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitCarrierPolicyRepository extends JpaRepository<BenefitCarrierPolicy, Long> {

    Optional<BenefitCarrierPolicy> findByCarrierAndSourceKey(Carrier carrier, String sourceKey);

    List<BenefitCarrierPolicy> findAllByCarrier(Carrier carrier);

    List<BenefitCarrierPolicy> findAllByBenefitIn(List<Benefit> benefits);
}
