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
                  AND (:filter IS NULL OR CASE
                       WHEN bcp.conditions->>'offerResolution' = 'UNRESOLVED' THEN false
                       WHEN bcp.conditions->>'offerResolution' IN ('SINGLE', 'SCOPED', 'PARTIAL') THEN EXISTS (
                           SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                           WHERE offer->>'usageType' IN (:filter, 'BOTH')
                             AND (:gradeFilterEnabled = false OR offer->>'allGrades' = 'true' OR EXISTS (
                                 SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                                 WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                     SELECT 1 FROM membership_grade_alias alias
                                     WHERE alias.carrier_code = bcp.carrier
                                       AND upper(alias.source_label) = upper(grade_code.code)
                                       AND alias.grade_code IN (:grades)
                                 )
                             ))
                       )
                       ELSE (:filter = 'ONLINE' AND bcp.usageType IN ('online', 'both')) OR
                            (:filter = 'OFFLINE' AND bcp.usageType IN ('offline', 'both'))
                  END)
                  AND (:keyword IS NULL OR
                       LOWER(p.partnerName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(p.partnerName) IN (:partnerBrands) OR
                       EXISTS (
                           SELECT 1 FROM partnerAlias pa
                           WHERE pa.partnerId = p.partnerId
                             AND (LOWER(pa.aliasName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                                  LOWER(pa.normalizedAlias) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                                  LOWER(pa.aliasName) IN (:partnerBrands) OR
                                  LOWER(pa.normalizedAlias) IN (:partnerBrands))
                       ) OR
                       EXISTS (
                           SELECT 1 FROM partner_category pc
                           WHERE pc.code = p.category
                             AND (LOWER(pc.code) = LOWER(:keyword) OR LOWER(pc.label) = LOWER(:keyword))
                       ) OR
                       EXISTS (
                           SELECT 1 FROM partner_category_alias pca
                           WHERE pca.category_code = p.category
                             AND LOWER(pca.source_label) = LOWER(
                                 CASE WHEN :keyword = '영화관' THEN '영화' ELSE :keyword END
                             )
                             AND LOWER(COALESCE(bcp.sourceCategory, '')) LIKE LOWER(CONCAT('%', pca.source_label, '%'))
                       ) OR
                       LOWER(COALESCE(b.benefitName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(bcp.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(bcp.sourceCategory, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       EXISTS (
                           SELECT 1 FROM carrierTierBenefit search_ctb
                           WHERE search_ctb.benefitCarrierPolicyId = bcp.benefitCarrierPolicyId
                             AND LOWER(COALESCE(search_ctb.context, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                             AND (
                                 (:gradeFilterEnabled = false AND :filter IS NULL) OR
                                 (search_ctb.offerKey IS NOT NULL AND EXISTS (
                                     SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                                     WHERE offer->>'key' = search_ctb.offerKey
                                       AND (:filter IS NULL OR offer->>'usageType' IN (:filter, 'BOTH'))
                                       AND (:gradeFilterEnabled = false OR offer->>'allGrades' = 'true' OR EXISTS (
                                           SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                                           WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                               SELECT 1 FROM membership_grade_alias alias
                                               WHERE alias.carrier_code = bcp.carrier
                                                 AND upper(alias.source_label) = upper(grade_code.code)
                                                 AND alias.grade_code IN (:grades)
                                           )
                                       ))
                                 )) OR
                                 (search_ctb.offerKey IS NULL AND CASE
                                     WHEN bcp.conditions->>'offerResolution' IN ('SINGLE', 'SCOPED', 'PARTIAL') THEN EXISTS (
                                         SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                                         WHERE (:filter IS NULL OR offer->>'usageType' IN (:filter, 'BOTH'))
                                           AND (:gradeFilterEnabled = false OR offer->>'allGrades' = 'true' OR EXISTS (
                                               SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                                               WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                                   SELECT 1 FROM membership_grade_alias alias
                                                   WHERE alias.carrier_code = bcp.carrier
                                                     AND upper(alias.source_label) = upper(grade_code.code)
                                                     AND alias.grade_code IN (:grades)
                                               )
                                           ))
                                     )
                                     ELSE (:gradeFilterEnabled = false OR search_ctb.grade IN (:grades)) AND
                                          (:filter IS NULL OR (:filter = 'ONLINE' AND bcp.usageType IN ('online', 'both')) OR
                                           (:filter = 'OFFLINE' AND bcp.usageType IN ('offline', 'both')))
                                 END)
                             )
                       ))
                  AND (:carrierFilterEnabled = false OR bcp.carrier IN (:carriers))
                  AND (:gradeFilterEnabled = false OR CASE
                       WHEN bcp.conditions->>'offerResolution' IN ('SINGLE', 'SCOPED', 'PARTIAL') AND :filter IS NOT NULL THEN true
                       WHEN bcp.conditions->>'offerResolution' IN ('SINGLE', 'SCOPED') THEN EXISTS (
                           SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                           WHERE offer->>'allGrades' = 'true' OR EXISTS (
                               SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                               WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                   SELECT 1 FROM membership_grade_alias alias
                                   WHERE alias.carrier_code = bcp.carrier
                                     AND upper(alias.source_label) = upper(grade_code.code)
                                     AND alias.grade_code IN (:grades)
                               )
                           )
                       )
                       WHEN bcp.conditions->>'offerResolution' = 'PARTIAL' THEN EXISTS (
                           SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                           WHERE offer->>'allGrades' = 'true' OR EXISTS (
                               SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                               WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                   SELECT 1 FROM membership_grade_alias alias
                                   WHERE alias.carrier_code = bcp.carrier
                                     AND upper(alias.source_label) = upper(grade_code.code)
                                     AND alias.grade_code IN (:grades)
                               )
                           )
                       ) OR EXISTS (
                           SELECT 1 FROM carrierTierBenefit ctb
                           WHERE ctb.benefitCarrierPolicyId = bcp.benefitCarrierPolicyId
                             AND ctb.grade IN (:grades)
                       )
                       ELSE EXISTS (
                           SELECT 1 FROM carrierTierBenefit ctb
                           WHERE ctb.benefitCarrierPolicyId = bcp.benefitCarrierPolicyId
                             AND ctb.grade IN (:grades)
                       )
                  END)
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
                  AND (:filter IS NULL OR CASE
                       WHEN bcp.conditions->>'offerResolution' = 'UNRESOLVED' THEN false
                       WHEN bcp.conditions->>'offerResolution' IN ('SINGLE', 'SCOPED', 'PARTIAL') THEN EXISTS (
                           SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                           WHERE offer->>'usageType' IN (:filter, 'BOTH')
                             AND (:gradeFilterEnabled = false OR offer->>'allGrades' = 'true' OR EXISTS (
                                 SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                                 WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                     SELECT 1 FROM membership_grade_alias alias
                                     WHERE alias.carrier_code = bcp.carrier
                                       AND upper(alias.source_label) = upper(grade_code.code)
                                       AND alias.grade_code IN (:grades)
                                 )
                             ))
                       )
                       ELSE (:filter = 'ONLINE' AND bcp.usageType IN ('online', 'both')) OR
                            (:filter = 'OFFLINE' AND bcp.usageType IN ('offline', 'both'))
                  END)
                  AND (:keyword IS NULL OR
                       LOWER(p.partnerName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(p.partnerName) IN (:partnerBrands) OR
                       EXISTS (
                           SELECT 1 FROM partnerAlias pa
                           WHERE pa.partnerId = p.partnerId
                             AND (LOWER(pa.aliasName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                                  LOWER(pa.normalizedAlias) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                                  LOWER(pa.aliasName) IN (:partnerBrands) OR
                                  LOWER(pa.normalizedAlias) IN (:partnerBrands))
                       ) OR
                       EXISTS (
                           SELECT 1 FROM partner_category pc
                           WHERE pc.code = p.category
                             AND (LOWER(pc.code) = LOWER(:keyword) OR LOWER(pc.label) = LOWER(:keyword))
                       ) OR
                       EXISTS (
                           SELECT 1 FROM partner_category_alias pca
                           WHERE pca.category_code = p.category
                             AND LOWER(pca.source_label) = LOWER(
                                 CASE WHEN :keyword = '영화관' THEN '영화' ELSE :keyword END
                             )
                             AND LOWER(COALESCE(bcp.sourceCategory, '')) LIKE LOWER(CONCAT('%', pca.source_label, '%'))
                       ) OR
                       LOWER(COALESCE(b.benefitName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(bcp.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       LOWER(COALESCE(bcp.sourceCategory, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) OR
                       EXISTS (
                           SELECT 1 FROM carrierTierBenefit search_ctb
                           WHERE search_ctb.benefitCarrierPolicyId = bcp.benefitCarrierPolicyId
                             AND LOWER(COALESCE(search_ctb.context, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                             AND (
                                 (:gradeFilterEnabled = false AND :filter IS NULL) OR
                                 (search_ctb.offerKey IS NOT NULL AND EXISTS (
                                     SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                                     WHERE offer->>'key' = search_ctb.offerKey
                                       AND (:filter IS NULL OR offer->>'usageType' IN (:filter, 'BOTH'))
                                       AND (:gradeFilterEnabled = false OR offer->>'allGrades' = 'true' OR EXISTS (
                                           SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                                           WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                               SELECT 1 FROM membership_grade_alias alias
                                               WHERE alias.carrier_code = bcp.carrier
                                                 AND upper(alias.source_label) = upper(grade_code.code)
                                                 AND alias.grade_code IN (:grades)
                                           )
                                       ))
                                 )) OR
                                 (search_ctb.offerKey IS NULL AND CASE
                                     WHEN bcp.conditions->>'offerResolution' IN ('SINGLE', 'SCOPED', 'PARTIAL') THEN EXISTS (
                                         SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                                         WHERE (:filter IS NULL OR offer->>'usageType' IN (:filter, 'BOTH'))
                                           AND (:gradeFilterEnabled = false OR offer->>'allGrades' = 'true' OR EXISTS (
                                               SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                                               WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                                   SELECT 1 FROM membership_grade_alias alias
                                                   WHERE alias.carrier_code = bcp.carrier
                                                     AND upper(alias.source_label) = upper(grade_code.code)
                                                     AND alias.grade_code IN (:grades)
                                               )
                                           ))
                                     )
                                     ELSE (:gradeFilterEnabled = false OR search_ctb.grade IN (:grades)) AND
                                          (:filter IS NULL OR (:filter = 'ONLINE' AND bcp.usageType IN ('online', 'both')) OR
                                           (:filter = 'OFFLINE' AND bcp.usageType IN ('offline', 'both')))
                                 END)
                             )
                       ))
                  AND (:carrierFilterEnabled = false OR bcp.carrier IN (:carriers))
                  AND (:gradeFilterEnabled = false OR CASE
                       WHEN bcp.conditions->>'offerResolution' IN ('SINGLE', 'SCOPED', 'PARTIAL') AND :filter IS NOT NULL THEN true
                       WHEN bcp.conditions->>'offerResolution' IN ('SINGLE', 'SCOPED') THEN EXISTS (
                           SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                           WHERE offer->>'allGrades' = 'true' OR EXISTS (
                               SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                               WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                   SELECT 1 FROM membership_grade_alias alias
                                   WHERE alias.carrier_code = bcp.carrier
                                     AND upper(alias.source_label) = upper(grade_code.code)
                                     AND alias.grade_code IN (:grades)
                               )
                           )
                       )
                       WHEN bcp.conditions->>'offerResolution' = 'PARTIAL' THEN EXISTS (
                           SELECT 1 FROM jsonb_array_elements(bcp.conditions->'offers') offer
                           WHERE offer->>'allGrades' = 'true' OR EXISTS (
                               SELECT 1 FROM jsonb_array_elements_text(offer->'gradeCodes') grade_code(code)
                               WHERE upper(grade_code.code) IN (:grades) OR EXISTS (
                                   SELECT 1 FROM membership_grade_alias alias
                                   WHERE alias.carrier_code = bcp.carrier
                                     AND upper(alias.source_label) = upper(grade_code.code)
                                     AND alias.grade_code IN (:grades)
                               )
                           )
                       ) OR EXISTS (
                           SELECT 1 FROM carrierTierBenefit ctb
                           WHERE ctb.benefitCarrierPolicyId = bcp.benefitCarrierPolicyId
                             AND ctb.grade IN (:grades)
                       )
                       ELSE EXISTS (
                           SELECT 1 FROM carrierTierBenefit ctb
                           WHERE ctb.benefitCarrierPolicyId = bcp.benefitCarrierPolicyId
                             AND ctb.grade IN (:grades)
                       )
                  END)
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
            @Param("partnerBrands") List<String> partnerBrands,
            @Param("carrierFilterEnabled") boolean carrierFilterEnabled,
            @Param("carriers") List<String> carriers,
            @Param("gradeFilterEnabled") boolean gradeFilterEnabled,
            @Param("grades") List<String> grades,
            @Param("sort") String sort,
            Pageable pageable
    );
}
