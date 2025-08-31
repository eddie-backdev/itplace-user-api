package com.itplace.userapi.recommend.service;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.Recommendations;
import com.itplace.userapi.recommend.entity.Recommendation;
import com.itplace.userapi.recommend.mapper.RecommendationMapper;
import com.itplace.userapi.recommend.repository.RecommendationRepository;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {
    private static final int EXPIRED_DAYS = 1;

    private final UserFeatureService userFeatureService;
    private final RecommendationRepository recommendationRepository;
    private final UserRepository userRepository;
    private final BenefitRepository benefitRepository;

    // ✅ VectorStore 주입 (PgVectorConfig에서 만든 빈 이름을 명시)
    private final @Qualifier("pgVectorStore") VectorStore vectorStore;

    public List<Recommendations> recommend(Long userId, int topK) throws Exception {
        LocalDateTime threshold = LocalDateTime.now().minusDays(EXPIRED_DAYS); // n일 기준으로 추천 갱신

        // 최근 추천 기록 있으면 캐시된 추천 반환
        LocalDate latestRecommendationDate = recommendationRepository.findLatestRecommendationDate(userId, threshold);
        if (latestRecommendationDate != null) {
            List<Recommendation> saved = recommendationRepository
                    .findByUserIdAndCreatedDate(userId, latestRecommendationDate);
            if (!saved.isEmpty()) {
                return RecommendationMapper.toDtoList(saved);
            }
        }

        // 사용자 성향 정보 로딩
        UserFeature uf = userFeatureService.loadUserFeature(userId);

        // ✅ 벡터 검색 기반 추천 후보 (aiService 제거)
        List<Candidate> candidates = vectorSearch(uf, Math.max(topK * 5, 50));

        // ✅ 재랭킹 및 이유 생성 (간단 정렬 + reason 구성)
        List<Recommendations> recommendations = rerankAndExplain(uf, candidates, topK);

        // 저장
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        List<Recommendation> entities = recommendations.stream()
                .map(dto -> {
                    List<Benefit> benefits = dto.getBenefitIds().stream()
                            .map(benefitRepository::getReferenceById)
                            .toList();
                    return RecommendationMapper.toEntity(dto, user, benefits);
                })
                .toList();

        recommendationRepository.saveAll(entities);

        return recommendations;
    }

    // ------------------------ 여기부터 내부 구현(이 파일 안에서만 추가) ------------------------

    /**
     * VectorStore 유사도 검색 → Candidate 리스트 변환
     */
    private List<Candidate> vectorSearch(UserFeature uf, int topN) {
        String query = buildQueryFromUserFeature(uf);

        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topN)
                        .build()
        );

        List<Candidate> list = new ArrayList<>();
        for (Document d : docs) {
            Map<String, Object> meta = d.getMetadata();

            Object benefitIdRaw = meta.get("benefitId");
            Object partnerIdRaw = meta.get("partnerId");

            if (benefitIdRaw == null || partnerIdRaw == null) {
                continue; // 필수 키 없으면 스킵
            }

            Long benefitId = toLong(benefitIdRaw);
            Long partnerId = toLong(partnerIdRaw);

            String benefitName = str(meta.get("benefitName"), d.getText());
            String partnerName = str(meta.get("partnerName"), "UNKNOWN");
            String category = str(meta.get("category"), "UNKNOWN");
            String description = str(meta.get("description"), "");
            String context = str(meta.get("context"), "");

            list.add(Candidate.builder()
                    .partnerId(partnerId)
                    .benefitId(benefitId)
                    .benefitName(benefitName)
                    .partnerName(partnerName)
                    .category(category)
                    .description(description)
                    .context(context)
                    .build());
        }
        return list;
    }

    /**
     * 간단 재랭킹 후 Recommendations로 변환 (VectorStore가 score를 주지 않으니 현재 순서를 신뢰)
     */
    private List<Recommendations> rerankAndExplain(UserFeature uf, List<Candidate> candidates, int topK) {
        // 상위 K만 사용 (VectorStore가 유사도순으로 주므로 그대로 신뢰)
        List<Candidate> top = candidates.stream().limit(topK).toList();

        // 간단한 이유 문구 (필요시 프로젝트 규칙에 맞게 조정)
        String reason = buildSimpleReason(uf, top);

        // 추천에 포함할 benefitId 목록
        List<Long> benefitIds = top.stream()
                .map(Candidate::getBenefitId)
                .toList();

        // 대표 파트너명은 상위 1개에서 가져오되, 없으면 베네핏명으로 대체
        String partnerName = top.isEmpty()
                ? "UNKNOWN"
                : (top.get(0).getPartnerName() != null && !top.get(0).getPartnerName().isBlank()
                        ? top.get(0).getPartnerName()
                        : (top.get(0).getBenefitName() != null ? top.get(0).getBenefitName() : "UNKNOWN"));

        // imgUrl은 현재 Candidate에 없으므로 빈 문자열로 세팅(추후 필요 시 metadata로 확장)
        Recommendations rec = Recommendations.builder()
                .rank(1)
                .partnerName(partnerName)
                .reason(reason)
                .imgUrl("")
                .benefitIds(benefitIds)
                .build();

        return List.of(rec);
    }

    private String buildQueryFromUserFeature(UserFeature uf) {
        // 필요하면 UserFeature 필드로 쿼리 구성. 여기선 안전하게 문자열화.
        return uf == null ? "" : uf.toString();
    }

    private String buildSimpleReason(UserFeature uf, List<Candidate> top) {
        String names = top.stream()
                .map(c -> c.getBenefitName() != null ? c.getBenefitName() : c.getPartnerName())
                .filter(Objects::nonNull)
                .limit(5)
                .collect(Collectors.joining(", "));
        return names.isEmpty()
                ? "선호도에 맞는 후보를 찾았습니다."
                : "선호도에 맞춰 " + names + "을(를) 추천합니다.";
    }

    private static Long toLong(Object o) {
        return (o instanceof Number) ? ((Number) o).longValue() : Long.valueOf(String.valueOf(o));
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }
}
