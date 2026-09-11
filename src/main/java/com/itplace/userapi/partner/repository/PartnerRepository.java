package com.itplace.userapi.partner.repository;

import com.itplace.userapi.partner.entity.Partner;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PartnerRepository extends JpaRepository<Partner, Long> {

    @Transactional(readOnly = true, timeout = 5)
    Optional<Partner> findByPartnerId(Long partnerId);

    @Transactional(readOnly = true, timeout = 5)
    Optional<Partner> findByPartnerName(String partnerName);

    @Transactional(readOnly = true, timeout = 5)
    List<Partner> findAllByPartnerName(String partnerName);

    @Transactional(readOnly = true, timeout = 5)
    List<Partner> findAllByPartnerNameIn(List<String> partnerNames);

    @Transactional(readOnly = true, timeout = 5)
    @Query(
            value = """
                SELECT p.*
                FROM partner p
                JOIN benefit b ON b.partnerId = p.partnerId
                JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                WHERE (:mainCategory IS NULL OR b.mainCategory = :mainCategory)
                  AND (:category IS NULL OR p.category = :category)
                  AND (:filter IS NULL OR
                       (:filter = 'ONLINE' AND bcp.usageType IN ('online', 'both')) OR
                       (:filter = 'OFFLINE' AND bcp.usageType IN ('offline', 'both')))
                  AND (:keyword IS NULL OR
                       LOWER(p.partnerName) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:carrierFilterEnabled = false OR bcp.carrier IN (:carriers))
                  AND COALESCE(b.active, true) = true
                  AND COALESCE(bcp.active, true) = true
                GROUP BY p.partnerId
                ORDER BY
                  CASE WHEN :sort = 'NAME_ASC' THEN LOWER(p.partnerName) END ASC,
                  CASE WHEN :sort = 'NAME_DESC' THEN LOWER(p.partnerName) END DESC,
                  CASE WHEN :sort = 'LATEST' THEN MAX(b.benefitId) END DESC,
                  CASE WHEN :sort = 'POPULARITY' THEN (
                      SELECT COUNT(*)
                      FROM favorite pf
                      JOIN benefit pb ON pb.benefitId = pf.benefitId
                      WHERE pb.partnerId = p.partnerId
                  ) END DESC,
                  LOWER(p.partnerName) ASC,
                  p.partnerId ASC
            """,
            countQuery = """
                SELECT COUNT(DISTINCT p.partnerId)
                FROM partner p
                JOIN benefit b ON b.partnerId = p.partnerId
                JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                WHERE (:mainCategory IS NULL OR b.mainCategory = :mainCategory)
                  AND (:category IS NULL OR p.category = :category)
                  AND (:filter IS NULL OR
                       (:filter = 'ONLINE' AND bcp.usageType IN ('online', 'both')) OR
                       (:filter = 'OFFLINE' AND bcp.usageType IN ('offline', 'both')))
                  AND (:keyword IS NULL OR
                       LOWER(p.partnerName) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:carrierFilterEnabled = false OR bcp.carrier IN (:carriers))
                  AND COALESCE(b.active, true) = true
                  AND COALESCE(bcp.active, true) = true
            """,
            nativeQuery = true
    )
    Page<Partner> findBenefitPartners(
            @Param("mainCategory") String mainCategory,
            @Param("category") String category,
            @Param("filter") String filter,
            @Param("keyword") String keyword,
            @Param("carrierFilterEnabled") boolean carrierFilterEnabled,
            @Param("carriers") List<String> carriers,
            @Param("sort") String sort,
            Pageable pageable
    );
}
