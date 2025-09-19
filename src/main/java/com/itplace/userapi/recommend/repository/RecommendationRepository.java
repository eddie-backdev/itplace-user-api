package com.itplace.userapi.recommend.repository;

import com.itplace.userapi.recommend.entity.Recommendation;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    @Query("SELECT MAX(DATE(r.createdDate)) FROM Recommendation r WHERE r.user.id = :userId AND r.createdDate >= :threshold")
    LocalDate findLatestRecommendationDate(@Param("userId") Long userId, @Param("threshold") LocalDateTime threshold);

    @Query("SELECT r FROM Recommendation r WHERE r.user.id = :userId AND DATE(r.createdDate) = :createdDate ORDER BY r.rank ASC")
    List<Recommendation> findByUserIdAndCreatedDate(@Param("userId") Long userId,
                                                    @Param("createdDate") LocalDate createdDate);
}
