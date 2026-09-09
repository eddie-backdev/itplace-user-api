package com.itplace.userapi.recommend.repository;

import com.itplace.userapi.recommend.entity.Recommendation;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;


public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
    Optional<Recommendation> findFirstByUser_IdAndActiveTrueAndCreatedDateGreaterThanEqualOrderByCreatedDateDesc(
            Long userId,
            LocalDateTime threshold
    );

    List<Recommendation> findByUser_IdAndCacheBatchIdAndActiveTrueOrderByRankAsc(Long userId, String cacheBatchId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Recommendation r SET r.active = false WHERE r.user.id = :userId AND r.active = true")
    int deactivateActiveByUserId(@Param("userId") Long userId);
}
