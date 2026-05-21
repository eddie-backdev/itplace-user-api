package com.itplace.userapi.recommend.repository;

import com.itplace.userapi.recommend.entity.RecommendationRankTraceEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRankTraceRepository extends JpaRepository<RecommendationRankTraceEntity, Long> {
    Optional<RecommendationRankTraceEntity> findByRequestId(String requestId);
}
