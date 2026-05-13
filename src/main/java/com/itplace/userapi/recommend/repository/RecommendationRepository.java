package com.itplace.userapi.recommend.repository;

import com.itplace.userapi.recommend.entity.Recommendation;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    @Query("SELECT MAX(r.createdDate) FROM Recommendation r WHERE r.user.id = :userId AND r.createdDate >= :threshold")
    LocalDateTime findLatestRecommendationCreatedDate(@Param("userId") Long userId, @Param("threshold") LocalDateTime threshold);

    @Query("SELECT r FROM Recommendation r WHERE r.user.id = :userId AND CAST(r.createdDate AS LocalDate) = :createdDate ORDER BY r.rank ASC")
    List<Recommendation> findByUserIdAndCreatedDate(@Param("userId") Long userId,
                                                    @Param("createdDate") LocalDate createdDate);

    Optional<Recommendation> findFirstByUser_IdAndActiveTrueAndCreatedDateGreaterThanEqualOrderByCreatedDateDesc(
            Long userId,
            LocalDateTime threshold
    );

    List<Recommendation> findByUser_IdAndCacheBatchIdAndActiveTrueOrderByRankAsc(Long userId, String cacheBatchId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Recommendation r SET r.active = false WHERE r.user.id = :userId AND r.active = true")
    int deactivateActiveByUserId(@Param("userId") Long userId);
}
