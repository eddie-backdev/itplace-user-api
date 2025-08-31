package com.itplace.userapi.recommend.mapper;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.recommend.dto.Recommendations;
import com.itplace.userapi.recommend.entity.Recommendation;
import com.itplace.userapi.user.entity.User;
import java.util.List;

public class RecommendationMapper {
    public static Recommendation toEntity(Recommendations dto, User user, List<Benefit> benefits) {
        return Recommendation.builder()
                .user(user)
                .rank(dto.getRank())
                .partnerName(dto.getPartnerName())
                .reason(dto.getReason())
                .imgUrl(dto.getImgUrl())
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
                .build();
    }

    public static List<Recommendations> toDtoList(List<Recommendation> entities) {
        return entities.stream()
                .map(RecommendationMapper::toDto)
                .toList();
    }
}

