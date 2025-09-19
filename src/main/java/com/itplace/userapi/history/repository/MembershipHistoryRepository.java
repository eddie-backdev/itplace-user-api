package com.itplace.userapi.history.repository;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.history.entity.MembershipHistory;
import com.itplace.userapi.recommend.projection.BenefitCount;
import com.itplace.userapi.recommend.projection.CategoryCount;
import com.itplace.userapi.user.entity.Membership;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipHistoryRepository extends JpaRepository<MembershipHistory, Long> {

    @Query("""
                SELECT mh
                FROM MembershipHistory mh
                JOIN FETCH mh.benefit b
                JOIN FETCH b.partner p
                WHERE mh.membership.membershipId = :membershipId
                  AND (:keyword IS NULL OR LOWER(b.benefitName) LIKE LOWER(CONCAT('%', :keyword, '%')))
                  AND (:startDate IS NULL OR mh.usedAt >= :startDate)
                  AND (:endDate IS NULL OR mh.usedAt <= :endDate)
                ORDER BY mh.usedAt DESC
            """)
    Page<MembershipHistory> findFiltered(
            @Param("membershipId") String membershipId,
            @Param("keyword") String keyword,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );

    @Query("""
                SELECT COALESCE(SUM(mh.discountAmount), 0)
                FROM MembershipHistory mh
                WHERE mh.membership.membershipId = :membershipId
                  AND YEAR(mh.usedAt) = :year
                  AND MONTH(mh.usedAt) = :month
            """)
    Long sumDiscountAmountThisMonth(
            @Param("membershipId") String membershipId,
            @Param("year") int year,
            @Param("month") int month
    );

    @Query("""
              SELECT p.category AS category, COUNT(mh) AS cnt
              FROM MembershipHistory mh
                JOIN mh.benefit b
                JOIN b.partner p
              WHERE mh.membership.membershipId = :membershipId
                AND mh.usedAt >= :since
              GROUP BY p.category
            """)
    List<CategoryCount> countByPartnerCategorySince(
            @Param("membershipId") String membershipId,
            @Param("since") LocalDateTime since
    );

    @Query("""
              SELECT mh.benefit.benefitId AS benefitId, COUNT(mh) AS cnt
              FROM MembershipHistory mh
              WHERE mh.membership.membershipId = :membershipId
                AND mh.usedAt >= :since
              GROUP BY mh.benefit.benefitId
            """)
    List<BenefitCount> countByBenefitSince(
            @Param("membershipId") String membershipId,
            @Param("since") LocalDateTime since
    );


    boolean existsByMembershipAndBenefit(Membership membership, Benefit benefit);

    boolean existsByMembershipAndBenefitAndUsedAtBetween(
            Membership membership, Benefit benefit, LocalDateTime start, LocalDateTime end
    );
}
