package com.itplace.userapi.recommend.service;

import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.history.repository.MembershipHistoryRepository;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.projection.BenefitCount;
import com.itplace.userapi.recommend.projection.CategoryCount;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.exception.UserNotFoundException;
import com.itplace.userapi.user.entity.Membership;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.MembershipRepository;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserFeatureServiceImpl implements UserFeatureService {
    private final MembershipHistoryRepository historyRepo;
    private final UserRepository userRepo;
    private final EmbeddingService embeddingService;
    private final BenefitRepository benefitRepo;
    private final MembershipRepository membershipRepo;
    private final LogRepository logRepository;

    public UserFeature loadUserFeature(Long userId) {
        LocalDateTime since = LocalDateTime.now().minusYears(1);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        String membershipId = user.getMembershipId();

        // 로그 기반 정보 수집 (클릭,상세,검색)
        List<String> clickPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "click", 3);
        List<String> searchPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "search", 3);
        List<String> detailPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "detail", 3);

        // 콜드 스타트 (멤버십 이용 내역 X)
        if (membershipId == null || membershipId.isBlank()) {
            return UserFeature.builder()
                    .userId(userId)
                    .grade(null)
                    .recentCategoryScores(Map.of())
                    .topCategories(List.of())
                    .benefitUsageCounts(Map.of())
                    .recentPartnerNames(List.of())
                    .clickPartners(clickPartners)
                    .searchPartners(searchPartners)
                    .detailPartners(detailPartners)
                    .build();
        }

        // 멤버십 사용자 처리
        Grade grade = membershipRepo.findByMembershipId(membershipId)
                .map(Membership::getGrade)
                .orElse(null);

        // 카테고리별 이용 횟수
        Map<String, Integer> catScores = historyRepo
                .countByPartnerCategorySince(membershipId, since).stream()
                .collect(Collectors.toMap(
                        CategoryCount::getCategory,
                        cc -> cc.getCnt().intValue()
                ));
        // 혜택별 이용 횟수
        Map<Long, Integer> benefitUsage = historyRepo
                .countByBenefitSince(membershipId, since).stream()
                .collect(Collectors.toMap(
                        BenefitCount::getBenefitId,
                        bc -> bc.getCnt().intValue()
                ));

        // 상위 4개 카테고리
        var topCats = catScores.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(4)
                .map(Map.Entry::getKey)
                .toList();

        // 상위 제휴사 이름 추출
        List<String> topPartners = benefitUsage.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .map(e -> {
                    Benefit b = benefitRepo.findById(e.getKey())
                            .orElseThrow(() -> new RuntimeException("혜택 ID 없음: " + e.getKey()));
                    return b.getPartner().getPartnerName();
                })
                .distinct()
                .limit(5)
                .toList();

        return UserFeature.builder()
                .userId(userId)
                .grade(grade)
                .recentCategoryScores(catScores)
                .topCategories(topCats)
                .benefitUsageCounts(benefitUsage)
                .recentPartnerNames(topPartners)
                .clickPartners(clickPartners)
                .searchPartners(searchPartners)
                .detailPartners(detailPartners)
                .build();

    }


    public float[] embedUserFeatures(UserFeature uf) {
        return embeddingService.embed(uf.getEmbeddingText());
    }

    public String getUserEmbeddingContext(UserFeature uf) {
        return uf.getEmbeddingText(); // UserFeature 내부 메서드 사용
    }


}
