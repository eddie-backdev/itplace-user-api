package com.itplace.userapi.recommend.mapper;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.entity.Recommendation;
import com.itplace.userapi.user.entity.User;
import java.util.List;

public class RecommendationMapper {
    public static Recommendation toEntity(Recommendations dto, User user, List<Benefit> benefits) {
        return toEntity(dto, user, benefits, null, "personalized-es-quality-v1");
    }

    public static Recommendation toEntity(Recommendations dto, User user, List<Benefit> benefits,
                                          String cacheBatchId, String algorithmVersion) {
        return Recommendation.builder()
                .user(user)
                .rank(dto.getRank())
                .partnerName(dto.getPartnerName())
                .reason(dto.getReason())
                .imgUrl(dto.getImgUrl())
                .cacheBatchId(cacheBatchId)
                .requestId(dto.getRequestId())
                .impressionId(dto.getImpressionId())
                .candidateSource(dto.getCandidateSource())
                .algorithmVersion(algorithmVersion)
                .active(true)
                .benefits(benefits)
                .build();
    }

    public static Recommendations toDto(Recommendation entity) {
        List<Long> benefitIds = entity.getBenefits().stream()
                .map(Benefit::getBenefitId)
                .toList();

        return Recommendations.builder()
                .rank(entity.getRank())
                .partnerName(entity.getPartnerName())
                .reason(entity.getReason())
                .imgUrl(entity.getImgUrl())
                .benefitIds(benefitIds)
                .requestId(entity.getRequestId())
                .impressionId(entity.getImpressionId())
                .candidateSource(entity.getCandidateSource())
                .algorithmVersion(entity.getAlgorithmVersion())
                .build();
    }

    public static List<Recommendations> toDtoList(List<Recommendation> entities) {
        return entities.stream()
                .map(RecommendationMapper::toDto)
                .toList();
    }
}
