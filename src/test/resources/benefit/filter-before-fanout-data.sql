SELECT b.* FROM benefit b
                LEFT JOIN partner p ON p.partnerId = b.partnerId
                LEFT JOIN benefitCarrierPolicy bcp ON bcp.benefitId = b.benefitId
                LEFT JOIN favorite f ON f.benefitId = b.benefitId
                WHERE (:mainCategory IS NULL OR b.mainCategory = :mainCategory)
                  AND (:category IS NULL OR p.category = :category)
                  AND (:filter IS NULL OR
                       (:filter = 'ONLINE' AND bcp.usageType IN ('online', 'both')) OR
                       (:filter = 'OFFLINE' AND bcp.usageType IN ('offline', 'both')))
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
                  COUNT(DISTINCT f.userId) DESC,
                  b.benefitId ASC
