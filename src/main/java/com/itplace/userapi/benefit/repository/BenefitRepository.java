package com.itplace.userapi.benefit.repository;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BenefitRepository extends JpaRepository<Benefit, Long> {
    @Query("""
                SELECT b FROM Benefit b
                JOIN FETCH b.partner p
                LEFT JOIN FETCH b.tierBenefits tb
                WHERE b.benefitId = :benefitId
            """)
    Optional<Benefit> findDetailById(@Param("benefitId") Long benefitId);

    List<Benefit> findAllByPartner_PartnerId(Long PartnerId);

    @Query("SELECT b FROM Benefit b JOIN FETCH b.partner WHERE b.partner.partnerId IN :partnerIds")
    List<Benefit> findAllByPartnerIdsWithPartner(@Param("partnerIds") List<Long> partnerIds);

    @Query("SELECT b FROM Benefit b JOIN FETCH b.partner WHERE b.benefitId IN :benefitIds")
    List<Benefit> findAllByIdWithPartner(@Param("benefitIds") List<Long> benefitIds);

    @Query(
            value = """
                SELECT b.* FROM benefit b
                LEFT JOIN partner p ON p.partnerId = b.partnerId
                LEFT JOIN favorite f ON f.benefitId = b.benefitId
                WHERE b.mainCategory = :mainCategory
                  AND (:category IS NULL OR p.category = :category)
                  AND (:filter IS NULL OR
                       (:filter = 'ONLINE' AND b.usageType IN ('ONLINE', 'BOTH')) OR
                       (:filter = 'OFFLINE' AND b.usageType IN ('OFFLINE', 'BOTH')))
                  AND (:keyword IS NULL OR LOWER(b.benefitName) LIKE LOWER(CONCAT('%', :keyword, '%')))
                GROUP BY b.benefitId
                ORDER BY COUNT(f.benefitId) DESC
            """,
            countQuery = """
                SELECT COUNT(DISTINCT b.benefitId) FROM benefit b
                LEFT JOIN partner p ON p.partnerId = b.partnerId
                LEFT JOIN favorite f ON f.benefitId = b.benefitId
                WHERE b.mainCategory = :mainCategory
                  AND (:category IS NULL OR p.category = :category)
                  AND (:filter IS NULL OR
                       (:filter = 'ONLINE' AND b.usageType IN ('ONLINE', 'BOTH')) OR
                       (:filter = 'OFFLINE' AND b.usageType IN ('OFFLINE', 'BOTH')))
                  AND (:keyword IS NULL OR LOWER(b.benefitName) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """,
            nativeQuery = true
    )
    Page<Benefit> findFilteredBenefits(
            @Param("mainCategory") String mainCategory,
            @Param("category") String category,
            @Param("filter") String filter,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
                SELECT b
                FROM Benefit b
                JOIN FETCH b.partner p
                WHERE b.benefitId = :benefitId
            """)
    Optional<Benefit> findBenefitWithPartnerById(@Param("benefitId") Long benefitId);

    //ElasticSearch Indexer 사용
    @Query("""
                SELECT b FROM Benefit b
                JOIN FETCH b.partner
                JOIN FETCH b.tierBenefits
            """)
    List<Benefit> findAllWithPartnerAndTierBenefits();

    List<Benefit> findByPartner_PartnerId(Long partnerId);

    @Query("SELECT b FROM Benefit b JOIN FETCH b.benefitPolicy WHERE b.benefitId = :benefitId")
    Optional<Benefit> findByIdWithPolicy(@Param("benefitId") Long benefitId);

    @Query("""
                SELECT DISTINCT b 
                FROM Benefit b
                JOIN FETCH b.partner p
                LEFT JOIN FETCH b.tierBenefits tb
                WHERE b.partner.partnerId = :partnerId 
                  AND b.mainCategory = :mainCategory
            """)
    List<Benefit> findBenefitsWithPartnerAndTierBenefits(
            @Param("partnerId") Long partnerId,
            @Param("mainCategory") MainCategory mainCategory
    );
}
