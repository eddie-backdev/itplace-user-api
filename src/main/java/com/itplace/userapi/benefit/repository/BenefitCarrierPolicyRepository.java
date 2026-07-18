package com.itplace.userapi.benefit.repository;

import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BenefitCarrierPolicyRepository extends JpaRepository<BenefitCarrierPolicy, Long> {

    Optional<BenefitCarrierPolicy> findByCarrierAndSourceKey(Carrier carrier, String sourceKey);

    @Query("""
                SELECT p
                FROM BenefitCarrierPolicy p
                JOIN FETCH p.benefit b
                JOIN FETCH b.partner
                WHERE p.carrier = :carrier
                  AND p.sourceKey IN :sourceKeys
            """)
    List<BenefitCarrierPolicy> findAllByCarrierAndSourceKeyInWithBenefit(
            @Param("carrier") Carrier carrier,
            @Param("sourceKeys") List<String> sourceKeys
    );

    List<BenefitCarrierPolicy> findAllByCarrier(Carrier carrier);

    @Query("""
            SELECT p FROM BenefitCarrierPolicy p
            JOIN FETCH p.benefit b
            LEFT JOIN FETCH p.benefitPolicy
            WHERE b IN :benefits
            """)
    List<BenefitCarrierPolicy> findAllByBenefitIn(@Param("benefits") List<Benefit> benefits);
}
