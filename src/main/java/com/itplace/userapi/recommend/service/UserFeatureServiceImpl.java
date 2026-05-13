package com.itplace.userapi.recommend.service;

import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.favorite.entity.Favorite;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.history.repository.MembershipHistoryRepository;
import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.projection.BenefitCount;
import com.itplace.userapi.recommend.projection.CategoryCount;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.repository.UserRepository;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserFeatureServiceImpl implements UserFeatureService {
    static final String FEATURE_SNAPSHOT_VERSION = "personalized-es-quality-v1";
    private static final double CLICK_WEIGHT = 3.0;
    private static final double SEARCH_WEIGHT = 2.0;
    private static final double DETAIL_WEIGHT = 4.0;
    private static final double FAVORITE_WEIGHT = 7.0;
    private static final double BENEFIT_USE_WEIGHT = 10.0;
    private static final double IMPRESSION_FATIGUE_WEIGHT = 1.5;
    private static final double FAVORITE_REMOVE_NEGATIVE_WEIGHT = 4.0;
    private static final double DISMISS_NEGATIVE_WEIGHT = 8.0;
    private static final double SKIP_NEGATIVE_WEIGHT = 6.0;
    private static final double EXPLICIT_NEGATIVE_WEIGHT = 10.0;
    private static final int SIGNAL_PARTNER_LIMIT = 5;
    private static final int NEGATIVE_PARTNER_LIMIT = 10;
    private static final List<String> LOCATION_CONTEXT_EVENTS = List.of(
            "location_context",
            "nearby_store_context",
            "map_location"
    );
    private static final List<String> EXPLICIT_NEGATIVE_EVENTS = List.of(
            "negative",
            "negative_feedback",
            "feedback_negative",
            "not_interested"
    );

    private final MembershipHistoryRepository historyRepo;
    private final UserRepository userRepo;
    private final EmbeddingService embeddingService;
    private final BenefitRepository benefitRepo;
    private final LogRepository logRepository;
    private final FavoriteRepository favoriteRepository;

    public UserFeature loadUserFeature(Long userId) {
        LocalDateTime since = LocalDateTime.now().minusYears(1);

        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));
        String membershipId = user.getMembershipId();
        Carrier carrier = user.getCarrier();
        Grade profileGrade = user.getMembershipGradeCode();

        // 로그 기반 정보 수집 (클릭,상세,검색)
        List<String> clickPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "click", SIGNAL_PARTNER_LIMIT);
        List<String> searchPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "search", SIGNAL_PARTNER_LIMIT);
        List<String> detailPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "detail", SIGNAL_PARTNER_LIMIT);
        List<String> impressionPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "impression", NEGATIVE_PARTNER_LIMIT);
        List<String> dismissedPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "dismiss", NEGATIVE_PARTNER_LIMIT);
        List<String> skippedPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "skip", NEGATIVE_PARTNER_LIMIT);
        List<String> favoriteRemovedPartners = logRepository.aggregateTopPartnerNamesByEvent(userId, "favorite_remove", NEGATIVE_PARTNER_LIMIT);
        List<String> negativeFeedbackPartners = aggregateEventFamily(userId, EXPLICIT_NEGATIVE_EVENTS, NEGATIVE_PARTNER_LIMIT);
        String locationContext = resolveLocationContext(userId);
        List<String> favoritePartners = favoriteRepository.findByUserIdWithBenefitAndPartner(userId).stream()
                .map(Favorite::getBenefit)
                .filter(benefit -> benefit != null && benefit.getPartner() != null)
                .map(benefit -> benefit.getPartner().getPartnerName())
                .distinct()
                .limit(10)
                .toList();

        // 콜드 스타트 (멤버십 이용 내역 X)
        if (membershipId == null || membershipId.isBlank()) {
            Map<String, Double> partnerScores = buildPartnerScores(
                    clickPartners, searchPartners, detailPartners, favoritePartners, List.of());
            Map<String, Double> negativePartnerScores = buildNegativePartnerScores(
                    impressionPartners, clickPartners, detailPartners, favoritePartners,
                    favoriteRemovedPartners, dismissedPartners, skippedPartners, negativeFeedbackPartners);
            List<String> tombstonedPartners = buildTombstonedPartners(dismissedPartners, skippedPartners, negativeFeedbackPartners);
            return UserFeature.builder()
                    .userId(userId)
                    .carrier(carrier)
                    .grade(profileGrade)
                    .recentCategoryScores(Map.of())
                    .topCategories(List.of())
                    .benefitUsageCounts(Map.of())
                    .recentPartnerNames(topPartners(partnerScores, 5))
                    .clickPartners(clickPartners)
                    .searchPartners(searchPartners)
                    .detailPartners(detailPartners)
                    .favoritePartners(favoritePartners)
                    .impressionPartners(impressionPartners)
                    .dismissedPartners(dismissedPartners)
                    .skippedPartners(skippedPartners)
                    .negativeFeedbackPartners(negativeFeedbackPartners)
                    .tombstonedPartners(tombstonedPartners)
                    .partnerAffinityScores(partnerScores)
                    .categoryAffinityScores(Map.of())
                    .negativePartnerScores(negativePartnerScores)
                    .locationContext(locationContext)
                    .featureSnapshotVersion(FEATURE_SNAPSHOT_VERSION)
                    .build();
        }

        // Legacy usage history is still keyed by membershipId; new self-declared profiles use membershipGradeCode.
        Grade grade = profileGrade;

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
        List<Long> sortedBenefitIds = benefitUsage.entrySet().stream()
                .sorted(Map.Entry.<Long, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        Map<Long, Benefit> benefitMap = benefitRepo.findAllByIdWithPartner(sortedBenefitIds).stream()
                .collect(Collectors.toMap(Benefit::getBenefitId, b -> b));

        List<String> usagePartners = sortedBenefitIds.stream()
                .map(id -> {
                    Benefit b = benefitMap.get(id);
                    if (b == null) throw new RuntimeException("혜택 ID 없음: " + id);
                    return b.getPartner().getPartnerName();
                })
                .distinct()
                .limit(5)
                .toList();

        Map<String, Double> partnerScores = buildPartnerScores(
                clickPartners, searchPartners, detailPartners, favoritePartners, usagePartners);
        Map<String, Double> negativePartnerScores = buildNegativePartnerScores(
                impressionPartners, clickPartners, detailPartners, favoritePartners,
                favoriteRemovedPartners, dismissedPartners, skippedPartners, negativeFeedbackPartners);
        List<String> tombstonedPartners = buildTombstonedPartners(dismissedPartners, skippedPartners, negativeFeedbackPartners);
        Map<String, Double> categoryScores = catScores.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().doubleValue() * BENEFIT_USE_WEIGHT));

        return UserFeature.builder()
                .userId(userId)
                .carrier(carrier)
                .grade(grade)
                .recentCategoryScores(catScores)
                .topCategories(topCats)
                .benefitUsageCounts(benefitUsage)
                .recentPartnerNames(topPartners(partnerScores, 5))
                .clickPartners(clickPartners)
                .searchPartners(searchPartners)
                .detailPartners(detailPartners)
                .favoritePartners(favoritePartners)
                .impressionPartners(impressionPartners)
                .dismissedPartners(dismissedPartners)
                .skippedPartners(skippedPartners)
                .negativeFeedbackPartners(negativeFeedbackPartners)
                .tombstonedPartners(tombstonedPartners)
                .partnerAffinityScores(partnerScores)
                .categoryAffinityScores(categoryScores)
                .negativePartnerScores(negativePartnerScores)
                .locationContext(locationContext)
                .featureSnapshotVersion(FEATURE_SNAPSHOT_VERSION)
                .build();

    }


    public List<Float> embedUserFeatures(UserFeature uf) {
        return embeddingService.embed(uf.getEmbeddingText());
    }

    public String getUserEmbeddingContext(UserFeature uf) {
        return uf.getEmbeddingText(); // UserFeature 내부 메서드 사용
    }

    static Map<String, Double> buildPartnerScores(List<String> clickPartners,
                                                  List<String> searchPartners,
                                                  List<String> detailPartners,
                                                  List<String> favoritePartners,
                                                  List<String> usagePartners) {
        Map<String, Double> scores = new LinkedHashMap<>();
        addWeighted(scores, searchPartners, SEARCH_WEIGHT);
        addWeighted(scores, clickPartners, CLICK_WEIGHT);
        addWeighted(scores, detailPartners, DETAIL_WEIGHT);
        addWeighted(scores, favoritePartners, FAVORITE_WEIGHT);
        addWeighted(scores, usagePartners, BENEFIT_USE_WEIGHT);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    static Map<String, Double> buildNegativePartnerScores(List<String> impressionPartners,
                                                          List<String> clickPartners,
                                                          List<String> detailPartners,
                                                          List<String> favoritePartners,
                                                          List<String> favoriteRemovedPartners,
                                                          List<String> dismissedPartners,
                                                          List<String> skippedPartners,
                                                          List<String> negativeFeedbackPartners) {
        Map<String, Double> scores = new LinkedHashMap<>();
        addImpressionFatigue(scores, impressionPartners, clickPartners, detailPartners, favoritePartners);
        addWeighted(scores, favoriteRemovedPartners, FAVORITE_REMOVE_NEGATIVE_WEIGHT);
        addWeighted(scores, dismissedPartners, DISMISS_NEGATIVE_WEIGHT);
        addWeighted(scores, skippedPartners, SKIP_NEGATIVE_WEIGHT);
        addWeighted(scores, negativeFeedbackPartners, EXPLICIT_NEGATIVE_WEIGHT);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private static void addImpressionFatigue(Map<String, Double> scores,
                                             List<String> impressionPartners,
                                             List<String> clickPartners,
                                             List<String> detailPartners,
                                             List<String> favoritePartners) {
        for (String partner : safeList(impressionPartners)) {
            if (partner == null || partner.isBlank()) {
                continue;
            }
            if (safeList(clickPartners).contains(partner)
                    || safeList(detailPartners).contains(partner)
                    || safeList(favoritePartners).contains(partner)) {
                continue;
            }
            scores.merge(partner, IMPRESSION_FATIGUE_WEIGHT, Double::sum);
        }
    }

    private static List<String> buildTombstonedPartners(List<String> dismissedPartners,
                                                        List<String> skippedPartners,
                                                        List<String> negativeFeedbackPartners) {
        LinkedHashSet<String> partners = new LinkedHashSet<>();
        partners.addAll(safeList(dismissedPartners));
        partners.addAll(safeList(skippedPartners));
        partners.addAll(safeList(negativeFeedbackPartners));
        return partners.stream()
                .filter(partner -> partner != null && !partner.isBlank())
                .toList();
    }

    private List<String> aggregateEventFamily(Long userId, List<String> events, int topK) {
        LinkedHashSet<String> partners = new LinkedHashSet<>();
        for (String event : events) {
            partners.addAll(safeList(logRepository.aggregateTopPartnerNamesByEvent(userId, event, topK)));
        }
        return partners.stream().limit(topK).toList();
    }

    private String resolveLocationContext(Long userId) {
        return logRepository.findLatestParamByEvents(userId, LOCATION_CONTEXT_EVENTS)
                .map(UserFeatureServiceImpl::toKnownLocationContext)
                .orElse("UNKNOWN");
    }

    private static String toKnownLocationContext(String rawParam) {
        if (rawParam == null || rawParam.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = URLDecoder.decode(rawParam, StandardCharsets.UTF_8).trim();
        if (normalized.isBlank()) {
            return "UNKNOWN";
        }
        String lower = normalized.toLowerCase();
        boolean hasLocationSignal = lower.contains("lat=")
                || lower.contains("lng=")
                || lower.contains("lon=")
                || lower.contains("city=")
                || lower.contains("town=")
                || lower.contains("district=")
                || lower.contains("nearby");
        if (!hasLocationSignal) {
            return "UNKNOWN";
        }
        return "KNOWN(" + normalized.substring(0, Math.min(normalized.length(), 120)) + ")";
    }

    private static void addWeighted(Map<String, Double> scores, List<String> partners, double weight) {
        if (partners == null) {
            return;
        }
        for (int rank = 0; rank < partners.size(); rank++) {
            String partner = partners.get(rank);
            if (partner == null || partner.isBlank()) {
                continue;
            }
            double recencyRankFactor = Math.max(0.2, 1.0 - (rank * 0.1));
            scores.merge(partner, weight * recencyRankFactor, Double::sum);
        }
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static List<String> topPartners(Map<String, Double> scores, int limit) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }


}
