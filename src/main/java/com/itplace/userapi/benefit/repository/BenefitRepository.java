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
                WHERE b.benefitId = :benefitId
                  AND COALESCE(b.active, true) = true
            """)
    Optional<Benefit> findDetailById(@Param("benefitId") Long benefitId);

    List<Benefit> findAllByPartner_PartnerId(Long PartnerId);

    Optional<Benefit> findByPartner_PartnerIdAndCanonicalKey(Long partnerId, String canonicalKey);

    @Query("SELECT b FROM Benefit b JOIN FETCH b.partner WHERE b.partner.partnerId IN :partnerIds")
    List<Benefit> findAllByPartnerIdsWithPartner(@Param("partnerIds") List<Long> partnerIds);

    @Query("SELECT b FROM Benefit b JOIN FETCH b.partner WHERE b.benefitId IN :benefitIds")
    List<Benefit> findAllByIdWithPartner(@Param("benefitIds") List<Long> benefitIds);

    @Query(
            value = """
                SELECT b.* FROM benefit b
                LEFT JOIN partner p ON p.partnerId = b.partnerId
                LEFT JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                LEFT JOIN favorite f ON f.benefitId = b.benefitId
                WHERE (:mainCategory IS NULL OR b.mainCategory = :mainCategory)
                  AND (:category IS NULL OR p.category = :category)
                  AND (:filter IS NULL OR
                       (:filter = 'ONLINE' AND bcp.usageType IN ('ONLINE', 'BOTH')) OR
                       (:filter = 'OFFLINE' AND bcp.usageType IN ('OFFLINE', 'BOTH')))
                  AND (:keyword IS NULL OR (
                       LOWER(b.benefitName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(p.partnerName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(p.category, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(bcp.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(bcp.manual, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       EXISTS (
                           SELECT 1 FROM carrierTierBenefit ctb
                           WHERE ctb.benefitCarrierPolicyId = bcp.benefitCarrierPolicyId
                             AND LOWER(ctb.context) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       )
                  ))
                  AND (:carrierFilterEnabled = false OR bcp.carrier IN (:carriers))
                  AND COALESCE(b.active, true) = true
                  AND COALESCE(bcp.active, true) = true
                GROUP BY b.benefitId
                ORDER BY
                  CASE WHEN :sort = 'NAME_ASC' THEN LOWER(b.benefitName) END ASC,
                  CASE WHEN :sort = 'NAME_DESC' THEN LOWER(b.benefitName) END DESC,
                  CASE WHEN :sort = 'LATEST' THEN b.benefitId END DESC,
                  COUNT(f.benefitId) DESC,
                  b.benefitId ASC
            """,
            countQuery = """
                SELECT COUNT(DISTINCT b.benefitId) FROM benefit b
                LEFT JOIN partner p ON p.partnerId = b.partnerId
                LEFT JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                LEFT JOIN favorite f ON f.benefitId = b.benefitId
                WHERE (:mainCategory IS NULL OR b.mainCategory = :mainCategory)
                  AND (:category IS NULL OR p.category = :category)
                  AND (:filter IS NULL OR
                       (:filter = 'ONLINE' AND bcp.usageType IN ('ONLINE', 'BOTH')) OR
                       (:filter = 'OFFLINE' AND bcp.usageType IN ('OFFLINE', 'BOTH')))
                  AND (:keyword IS NULL OR (
                       LOWER(b.benefitName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(p.partnerName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(p.category, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(bcp.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(bcp.manual, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       EXISTS (
                           SELECT 1 FROM carrierTierBenefit ctb
                           WHERE ctb.benefitCarrierPolicyId = bcp.benefitCarrierPolicyId
                             AND LOWER(ctb.context) LIKE LOWER(CONCAT('%', :keyword, '%'))
                       )
                  ))
                  AND (:carrierFilterEnabled = false OR bcp.carrier IN (:carriers))
                  AND COALESCE(b.active, true) = true
                  AND COALESCE(bcp.active, true) = true
            """,
            nativeQuery = true
    )
    Page<Benefit> findFilteredBenefits(
            @Param("mainCategory") String mainCategory,
            @Param("category") String category,
            @Param("filter") String filter,
            @Param("keyword") String keyword,
            @Param("carrierFilterEnabled") boolean carrierFilterEnabled,
            @Param("carriers") List<String> carriers,
            @Param("sort") String sort,
            Pageable pageable
    );

    @Query("""
                SELECT b
                FROM Benefit b
                JOIN FETCH b.partner p
                WHERE b.benefitId = :benefitId
                  AND COALESCE(b.active, true) = true
            """)
    Optional<Benefit> findBenefitWithPartnerById(@Param("benefitId") Long benefitId);

    //ElasticSearch Indexer 사용
    @Query("""
                SELECT b FROM Benefit b
                JOIN FETCH b.partner
            """)
    List<Benefit> findAllWithPartnerAndTierBenefits();

    List<Benefit> findByPartner_PartnerId(Long partnerId);

    @Query("""
                SELECT DISTINCT b
                FROM Benefit b
                JOIN FETCH b.partner p
                WHERE b.partner.partnerId = :partnerId
                  AND (:mainCategory IS NULL OR b.mainCategory = :mainCategory)
                  AND COALESCE(b.active, true) = true
            """)
    List<Benefit> findMapBenefitsWithPartnerAndTierBenefits(
            @Param("partnerId") Long partnerId,
            @Param("mainCategory") MainCategory mainCategory
    );
}
