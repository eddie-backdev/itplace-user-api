package com.itplace.userapi.recommend.repository;

import com.itplace.userapi.recommend.entity.RecommendationRankTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRankTraceRepository extends JpaRepository<RecommendationRankTraceEntity, Long> {
}
