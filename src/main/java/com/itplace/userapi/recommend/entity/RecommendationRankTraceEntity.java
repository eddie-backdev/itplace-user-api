package com.itplace.userapi.recommend.entity;

import com.itplace.userapi.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recommendation_rank_traces")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationRankTraceEntity extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64, nullable = false)
    private String requestId;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 64, nullable = false)
    private String serviceType;

    @Column(length = 64, nullable = false)
    private String algorithmVersion;

    @Column(length = 64, nullable = false)
    private String experimentArm;

    @Column(length = 32, nullable = false)
    private String cacheStatus;

    @Column(length = 128)
    private String invalidationReason;

    @Column(nullable = false)
    private Boolean attributionComplete;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String traceJson;
}
