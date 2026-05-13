package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAIServiceImplRankingTest {

    private final OpenAIServiceImpl service = new OpenAIServiceImpl(null, null, null, null, null);

    @Test
    void deterministicRankCombinesSemanticAndBehaviorEvidence() {
        UserFeature feature = UserFeature.builder()
                .partnerAffinityScores(Map.of("Favorite", 12.0, "Semantic", 1.0))
                .categoryAffinityScores(Map.of("movie", 4.0))
                .favoritePartners(List.of("Favorite"))
                .clickPartners(List.of())
                .searchPartners(List.of())
                .detailPartners(List.of())
                .recentPartnerNames(List.of())
                .build();

        List<Candidate> ranked = service.deterministicRank(feature, List.of(
                Candidate.builder().benefitId(2L).partnerName("Semantic").category("movie").semanticScore(0.9).candidateSource("es_vector").build(),
                Candidate.builder().benefitId(1L).partnerName("Favorite").category("movie").semanticScore(0.4).candidateSource("es_vector").build()
        ));

        assertThat(ranked).extracting(Candidate::getPartnerName).containsExactly("Favorite", "Semantic");
        assertThat(ranked.get(0).getScoreComponents()).containsKeys(
                "semantic_similarity", "behavior_affinity", "category_affinity", "grounded_user_signal");
    }

    @Test
    void deterministicRankSubtractsNegativeSignalsAndTombstones() {
        UserFeature feature = UserFeature.builder()
                .partnerAffinityScores(Map.of("Healthy", 2.0, "Dismissed", 20.0))
                .categoryAffinityScores(Map.of())
                .negativePartnerScores(Map.of("Dismissed", 8.0))
                .tombstonedPartners(List.of("Dismissed"))
                .clickPartners(List.of())
                .searchPartners(List.of())
                .detailPartners(List.of())
                .favoritePartners(List.of())
                .recentPartnerNames(List.of())
                .build();

        List<Candidate> ranked = service.deterministicRank(feature, List.of(
                Candidate.builder().benefitId(1L).partnerName("Dismissed").semanticScore(0.9).candidateSource("es_vector").build(),
                Candidate.builder().benefitId(2L).partnerName("Healthy").semanticScore(0.7).candidateSource("es_vector").build()
        ));

        assertThat(ranked).extracting(Candidate::getPartnerName).containsExactly("Healthy", "Dismissed");
        assertThat(ranked.get(1).getScoreComponents())
                .containsEntry("negative_partner_penalty", 8.0)
                .containsEntry("tombstone_penalty", 50.0);
    }

    @Test
    void constrainRecommendationsKeepsOnlyPromptCandidatesDeduplicatesAndBackfills() {
        List<Candidate> candidates = List.of(
                Candidate.builder().benefitId(1L).partnerName("Allowed A").context("A context").build(),
                Candidate.builder().benefitId(2L).partnerName("Allowed B").context("B context").build(),
                Candidate.builder().benefitId(3L).partnerName("Allowed C").context("C context").build()
        );
        List<com.itplace.userapi.recommend.dto.response.Recommendations> llmOutput = List.of(
                com.itplace.userapi.recommend.dto.response.Recommendations.builder()
                        .rank(1).partnerName("Allowed B").reason("B reason").build(),
                com.itplace.userapi.recommend.dto.response.Recommendations.builder()
                        .rank(2).partnerName("Out Of Set").reason("bad").build(),
                com.itplace.userapi.recommend.dto.response.Recommendations.builder()
                        .rank(3).partnerName("Allowed B").reason("duplicate").build()
        );

        List<com.itplace.userapi.recommend.dto.response.Recommendations> result =
                service.constrainRecommendationsToCandidates(llmOutput, candidates, 3);

        assertThat(result).extracting(com.itplace.userapi.recommend.dto.response.Recommendations::getPartnerName)
                .containsExactly("Allowed B", "Allowed A", "Allowed C");
        assertThat(result).extracting(com.itplace.userapi.recommend.dto.response.Recommendations::getRank)
                .containsExactly(1, 2, 3);
        assertThat(result.get(0).getBenefitIds()).containsExactly(2L);
    }

}
