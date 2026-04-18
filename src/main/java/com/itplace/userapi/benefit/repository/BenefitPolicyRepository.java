package com.itplace.userapi.benefit.repository;

import com.itplace.userapi.benefit.entity.BenefitPolicy;
import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BenefitPolicyRepository extends JpaRepository<BenefitPolicy, Long> {

    Optional<BenefitPolicy> findByCode(BenefitPolicyCode code);
}
