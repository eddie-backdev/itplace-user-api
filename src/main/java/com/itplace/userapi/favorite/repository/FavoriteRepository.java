package com.itplace.userapi.favorite.repository;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.favorite.entity.Favorite;
import com.itplace.userapi.favorite.entity.FavoriteId;
import com.itplace.userapi.user.entity.User;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<Favorite, FavoriteId> {

    Page<Favorite> findByUser(User user, Pageable pageable);

    Page<Favorite> findByUserAndBenefit_MainCategory(User user, MainCategory mainCategory, Pageable pageable);

    @Query("""
                SELECT f FROM Favorite f
                JOIN FETCH f.benefit b
                JOIN FETCH b.partner
                WHERE f.user = :user
                  AND b.benefitName LIKE %:keyword%
            """)
    List<Favorite> findByUserAndBenefit_BenefitNameContaining(User user, String keyword);

    boolean existsByUserAndBenefit(User user, Benefit benefit);

    @Query("SELECT f.benefit.benefitId FROM Favorite f WHERE f.user.id = :userId AND f.benefit.benefitId IN :benefitIds")
    List<Long> findFavoriteBenefitIdsByUser(@Param("userId") Long userId, @Param("benefitIds") List<Long> benefitIds);


    @Query("SELECT f.benefit.benefitId, COUNT(f) FROM Favorite f WHERE f.benefit.benefitId IN :benefitIds GROUP BY f.benefit.benefitId")
    List<Object[]> countFavoritesByBenefitIds(@Param("benefitIds") List<Long> benefitIds);


    void deleteByUserAndBenefitIn(User user, List<Benefit> benefits);


    List<Favorite> findByUserAndBenefit_BenefitNameContainingAndBenefit_Partner_CategoryContaining(User user,
                                                                                                   String keyword,
                                                                                                   String category);

    boolean existsByUser_IdAndBenefit_BenefitId(Long userId, Long benefitId);

    void deleteByUser_Id(Long userId);
}
