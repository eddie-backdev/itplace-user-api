package com.itplace.userapi.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.ai.chat.dto.BenefitChatRequest;
import com.itplace.userapi.ai.chat.dto.BenefitChatResponse;
import com.itplace.userapi.ai.chat.intent.BenefitChatPlan;
import com.itplace.userapi.ai.chat.intent.BenefitChatPlanner;
import com.itplace.userapi.ai.chat.llm.BenefitChatAnswerer;
import com.itplace.userapi.ai.rag.service.BenefitSearchCondition;
import com.itplace.userapi.ai.rag.service.BenefitSearchService;
import com.itplace.userapi.ai.rag.service.BenefitSearchService.ChatSearchResult;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.BenefitFact;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.OfferResolution;
import com.itplace.userapi.benefit.service.BenefitOfferMatcher;
import com.itplace.userapi.reference.ReferenceDataService;
import com.itplace.userapi.map.service.StoreService;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.List;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BenefitChatServiceTest {
    private BenefitChatPlanner planner;
    private BenefitChatAnswerer answerer;
    private EmbeddingService embedding;
    private BenefitSearchService search;
    private BenefitChatService service;

    @BeforeEach
    void setUp() {
        planner = org.mockito.Mockito.mock(BenefitChatPlanner.class);
        answerer = org.mockito.Mockito.mock(BenefitChatAnswerer.class);
        embedding = org.mockito.Mockito.mock(EmbeddingService.class);
        search = org.mockito.Mockito.mock(BenefitSearchService.class);
        service = new BenefitChatService(planner, answerer, embedding, search,
                org.mockito.Mockito.mock(UserRepository.class), org.mockito.Mockito.mock(StoreService.class),
                org.mockito.Mockito.mock(com.itplace.userapi.benefit.service.BenefitOfferMatcher.class),
                org.mockito.Mockito.mock(com.itplace.userapi.reference.ReferenceDataService.class));
        ReflectionTestUtils.setField(service, "aiEnabled", true);
    }

    @Test
    void detailFollowUpResolvesPreviousCandidateAgainstCurrentSearch() {
        BenefitChatRequest request = request("더 자세히", List.of("benefit:11:policy:21:tier:31"));
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "DETAIL", "", true);
        Candidate candidate = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of(candidate));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("확인된 상세 혜택입니다.", List.of(candidate.getCandidateKey()), List.of()));

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("ANSWER");
        assertThat(result.partners()).extracting("candidateKey").containsExactly(candidate.getCandidateKey());
        verify(search, never()).queryChat(any(), any(), any(), anyString(), eq(40), any());
    }

    @Test
    void emptySuccessfulSearchIsNoResultsButUnavailableSearchIsUnavailable() {
        BenefitChatRequest request = request("영화 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("희귀한 혜택", List.of(), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "SEARCH", "", false);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(any(), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(), true))
                .thenReturn(new ChatSearchResult(List.of(), false));

        assertThat(service.chat(request, null).status()).isEqualTo("NO_RESULTS");
        assertThat(service.chat(request, null).status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void embeddingFailureFallsBackToScopedLexicalRetrieval() {
        BenefitChatRequest request = request("GS25 KT 골드 영화 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("GS25 영화 혜택", List.of("영화"), List.of("GS25"), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "SEARCH", "", false);
        Candidate gs25 = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        gs25.setPartnerName("GS25");
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenThrow(new IllegalStateException("embedding timeout"));
        when(search.queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), isNull(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(gs25), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("GS25 혜택입니다.", List.of(gs25.getCandidateKey()), List.of()));

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("ANSWER");
        assertThat(result.partners()).extracting("partnerName").containsExactly("GS25");
        verify(search).queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), isNull(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("MOVIE_THEATER"), List.of(), List.of(), List.of(),
                        List.of("GS25"), List.of())));
    }

    @Test
    void failedEmbeddingAndUnavailableRelationalRetrievalReturnsUnavailable() {
        BenefitChatRequest request = request("GS25 KT 골드 영화 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("GS25 영화 혜택", List.of("영화"), List.of("GS25"), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "SEARCH", "", false);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenThrow(new IllegalStateException("embedding timeout"));
        when(search.queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), isNull(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(), false));

        assertThat(service.chat(request, null).status()).isEqualTo("UNAVAILABLE");
        verify(search).queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), isNull(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("MOVIE_THEATER"), List.of(), List.of(), List.of(),
                        List.of("GS25"), List.of())));
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void embeddingCancellationPropagatesAndRestoresInterruptFlag() {
        BenefitChatRequest request = request("GS25 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("GS25 혜택", List.of(), List.of("GS25"), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "SEARCH", "", false);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenThrow(new RuntimeException(new InterruptedException("cancelled")));

        try {
            assertThatThrownBy(() -> service.chat(request, null)).isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(search, never()).queryChat(any(), any(), any(), anyString(), eq(40), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unavailablePartnerCatalogReturnsUnavailableBeforeEmbeddingOrRetrieval() {
        BenefitChatRequest request = request("GS25 혜택", List.of());
        when(planner.plan(eq(request), eq(null), eq(null))).thenThrow(
                new com.itplace.userapi.ai.chat.intent.PartnerCatalogResolver.CatalogUnavailableException(
                        new IllegalStateException("catalog unavailable")));

        assertThat(service.chat(request, null).status()).isEqualTo("UNAVAILABLE");
        verify(embedding, never()).embedQuery(anyString());
        verify(search, never()).queryChat(any(), any(), any(), anyString(), eq(40), any());
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void convenienceSearchPassesBusinessTypeFilterToRetrieval() {
        BenefitChatRequest request = request("편의점", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("편의점 혜택 편의점", List.of("편의점"), List.of(), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate candidate = Candidate.builder().candidateKey("benefit:11:policy:21:tier:31").benefitId(11L)
                .policyId(21L).tierBenefitId(31L).partnerId(9L).partnerName("GS25").benefitName("편의점 할인")
                .carrier("KT").grade("KT_GOLD").businessType("CONVENIENCE_STORE").category("").build();
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(any(), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(candidate), true));
        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("ANSWER");
        verify(search).queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("CONVENIENCE_STORE"), List.of(), List.of(), List.of())));
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void explicitPartnerSearchUsesExactCanonicalNameBeforeAnswering() {
        BenefitChatRequest request = request("GS25 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("GS25", List.of(), List.of("GS25"), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate lookalike = Candidate.builder().candidateKey("lookalike").benefitId(1L).policyId(2L)
                .partnerId(3L).partnerName("GS25 택배").businessType("CONVENIENCE_STORE").build();
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(lookalike), true));

        assertThat(service.chat(request, null).status()).isEqualTo("NO_RESULTS");
        verify(search).queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of(), List.of(), List.of(), List.of(), List.of("GS25"), List.of())));
    }

    @Test
    void movieSearchFiltersUnrelatedCandidatesAndCafeTopicsUseUnionTypes() {
        BenefitChatRequest request = request("영화 할인", List.of());
        BenefitChatPlan moviePlan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate movie = candidate("movie", 1L, 2L, 3L);
        Candidate culture = Candidate.builder().candidateKey("culture").benefitId(4L).policyId(5L).tierBenefitId(6L)
                .partnerId(10L).partnerName("초대혜택").benefitName("공연 응모").businessType("CULTURE").build();
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(moviePlan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(movie, culture), true));
        assertThat(service.chat(request, null).partners()).extracting("candidateKey").containsExactly("movie");
        verify(search).queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("MOVIE_THEATER"), List.of(), List.of(), List.of())));
        verify(answerer, never()).answer(any(), any(), anyList());

        BenefitChatPlan mixed = new BenefitChatPlan("편의점 카페 혜택", List.of("편의점", "카페/디저트"), List.of(), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(mixed);
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(), true));
        service.chat(request, null);
        verify(search).queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("CONVENIENCE_STORE", "BEVERAGE_CAFE", "DESSERT"), List.of(), List.of(), List.of())));
    }

    @Test
    void cafeTopicExcludesStudyAndKidsBusinessTypesFromCandidates() {
        BenefitChatRequest request = request("카페 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("카페/디저트 혜택", List.of("카페/디저트"), List.of(), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate cafe = Candidate.builder().candidateKey("cafe").benefitId(1L).policyId(2L).partnerId(3L)
                .partnerName("스타벅스").businessType("BEVERAGE_CAFE").build();
        Candidate study = Candidate.builder().candidateKey("study").benefitId(4L).policyId(5L).partnerId(6L)
                .partnerName("스터디카페").businessType("STUDY_SPACE").build();
        Candidate kids = Candidate.builder().candidateKey("kids").benefitId(7L).policyId(8L).partnerId(9L)
                .partnerName("키즈카페").businessType("KIDS_PLAY").build();
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(cafe, study, kids), true));
        assertThat(service.chat(request, null).partners()).extracting("candidateKey").containsExactly("cafe");
        verify(search).queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("BEVERAGE_CAFE", "DESSERT"), List.of(), List.of(), List.of())));
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void restaurantAndDrinkCategoriesBecomeBusinessTypeFiltersBeforeRetrievalLimit() {
        BenefitChatRequest request = request("외식 말고 음료 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("음료 혜택", List.of("음료"), List.of(), List.of("외식"),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate cafe = Candidate.builder().candidateKey("cafe").benefitId(1L).policyId(2L).partnerId(3L)
                .partnerName("카페").businessType("BEVERAGE_CAFE").build();
        Candidate restaurant = Candidate.builder().candidateKey("restaurant").benefitId(4L).policyId(5L).partnerId(6L)
                .partnerName("식당").businessType("FOOD_RESTAURANT").build();
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(restaurant, cafe), true));

        assertThat(service.chat(request, null).partners()).extracting("candidateKey").containsExactly("cafe");
        verify(search).queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("BEVERAGE_CAFE"), List.of("FOOD_RESTAURANT"),
                        List.of(), List.of(), List.of(), List.of())));
    }

    @Test
    void diningCategoryRequiresFoodRestaurantType() {
        BenefitChatRequest request = request("외식", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("외식 혜택", List.of("외식"), List.of(), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate food = Candidate.builder().candidateKey("food").benefitId(1L).policyId(2L).partnerId(3L)
                .partnerName("식당").businessType("FOOD_RESTAURANT").build();
        Candidate cafe = Candidate.builder().candidateKey("cafe").benefitId(4L).policyId(5L).partnerId(6L)
                .partnerName("카페").businessType("BEVERAGE_CAFE").build();
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(cafe, food), true));

        assertThat(service.chat(request, null).partners()).extracting("candidateKey").containsExactly("food");
        verify(search).queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("FOOD_RESTAURANT"), List.of(), List.of(), List.of())));
    }

    @Test
    void educationTopicExcludesStudyCafeCandidates() {
        BenefitChatRequest request = request("학원 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("학원 혜택", List.of("교육"), List.of(), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate academy = Candidate.builder().candidateKey("academy").benefitId(1L).policyId(2L).partnerId(3L)
                .partnerName("YBM 어학원").businessType("EDUCATION").build();
        Candidate studyCafe = Candidate.builder().candidateKey("study").benefitId(4L).policyId(5L).partnerId(6L)
                .partnerName("스터디카페").businessType("STUDY_SPACE").build();
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(academy, studyCafe), true));
        assertThat(service.chat(request, null).partners()).extracting("candidateKey").containsExactly("academy");
        verify(search).queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("EDUCATION"), List.of(), List.of(), List.of())));
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void preservesSeparatePolicyTierCandidatesForSameBenefit() {
        BenefitChatRequest request = request("통신사별 비교", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 비교", List.of("영화"), List.of(), List.of(), List.of(Carrier.KT, Carrier.SKT),
                null, true, "ANY", false, "", "COMPARE", "", false);
        Candidate kt = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        Candidate skt = candidate("benefit:11:policy:22:tier:32", 11L, 22L, 32L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any())).thenReturn(new ChatSearchResult(List.of(kt), true));
        when(search.queryChat(eq(Carrier.SKT), any(), any(), anyString(), eq(40), any())).thenReturn(new ChatSearchResult(List.of(skt), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") List<Candidate> found = call.getArgument(2);
            return new BenefitChatAnswerer.ChatAnswer("비교 가능한 혜택입니다.", found.stream().map(Candidate::getCandidateKey).toList(), List.of());
        });

        var result = service.chat(request, null);

        assertThat(result.partners()).extracting("candidateKey").containsExactly(kt.getCandidateKey(), skt.getCandidateKey());
    }

    @Test
    void newCrossCarrierRequestDoesNotReusePreviousCandidateKeys() {
        BenefitChatRequest request = request("통신사별 영화 혜택 비교", List.of("benefit:11:policy:21:tier:31"));
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택 비교", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT, Carrier.SKT), null, true, "ANY", false, "", "COMPARE", "", false);
        Candidate current = candidate("benefit:11:policy:22:tier:32", 11L, 22L, 32L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any())).thenReturn(new ChatSearchResult(List.of(), true));
        when(search.queryChat(eq(Carrier.SKT), any(), any(), anyString(), eq(40), any())).thenReturn(new ChatSearchResult(List.of(current), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("통신사별 혜택입니다.", List.of(current.getCandidateKey()), List.of()));

        var result = service.chat(request, null);

        assertThat(result.partners()).extracting("candidateKey").containsExactly(current.getCandidateKey());
        verify(search, never()).resolveChatCandidates(anyList(), any(), any());
    }

    @Test
    void resolvedPreviousCandidatesRespectCurrentPlanExclusions() {
        BenefitChatRequest request = request("이 혜택 자세히", List.of("benefit:11:policy:21:tier:31"));
        BenefitChatPlan plan = new BenefitChatPlan("영화 제휴처", List.of("영화"), List.of(), List.of("제휴처"),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "DETAIL", "", true);
        Candidate previous = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of(previous));
        when(search.queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(previous), true));

        assertThat(service.chat(request, null).status()).isEqualTo("CLARIFICATION");
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void explicitAlternativeDoesNotFallBackToThePreviouslyShownPartner() {
        BenefitChatRequest request = request("다른 곳", List.of("benefit:11:policy:21:tier:31"));
        BenefitChatPlan plan = new BenefitChatPlan("영화 다른 제휴처 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "SEARCH", "", true);
        Candidate previous = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of(previous));
        when(search.queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(previous), true));

        assertThat(service.chat(request, null).status()).isEqualTo("NO_RESULTS");
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void pronounAlternativeStillFiltersPreviouslyShownPartner() {
        BenefitChatRequest request = request("그거 말고", List.of("benefit:11:policy:21:tier:31"));
        BenefitChatPlan plan = new BenefitChatPlan("영화 다른 제휴처 혜택", List.of("영화"), List.of(), List.of("그거"),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "SEARCH", "", true);
        Candidate previous = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of(previous));
        when(search.queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(previous), true));

        assertThat(service.chat(request, null).status()).isEqualTo("NO_RESULTS");
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void namedPartnerExclusionKeepsOtherPreviouslyShownMoviePartners() {
        Candidate cgv = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        cgv.setPartnerId(11L);
        cgv.setPartnerName("CGV");
        Candidate lotte = candidate("benefit:12:policy:22:tier:32", 12L, 22L, 32L);
        lotte.setPartnerId(12L);
        lotte.setPartnerName("롯데시네마");
        Candidate megabox = candidate("benefit:13:policy:23:tier:33", 13L, 23L, 33L);
        megabox.setPartnerId(13L);
        megabox.setPartnerName("메가박스");
        BenefitChatRequest request = request("CGV 말고", List.of(cgv.getCandidateKey(), lotte.getCandidateKey(), megabox.getCandidateKey()));
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of("CGV"),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "SEARCH", "", true);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of(cgv, lotte, megabox));
        when(search.queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(cgv, lotte, megabox), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") List<Candidate> found = call.getArgument(2);
            return new BenefitChatAnswerer.ChatAnswer("영화 혜택입니다.", found.stream().map(Candidate::getCandidateKey).toList(), List.of());
        });

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("ANSWER");
        assertThat(result.partners()).extracting("candidateKey").containsExactly(lotte.getCandidateKey(), megabox.getCandidateKey());
        assertThat(result.partners()).extracting("partnerName").doesNotContain("CGV");
        verify(search).queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), any(), anyString(), eq(40),
                eq(new BenefitSearchCondition(List.of("MOVIE_THEATER"), List.of(), List.of(), List.of(), List.of(), List.of("CGV"))));
    }

    @Test
    void comparisonContextInterleavesCarriersBeforeTheAnswererLimit() {
        BenefitChatRequest request = request("영화 통신사별 혜택 비교", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 비교", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.SKT, Carrier.KT, Carrier.LGU), null, true, "ANY", false, "", "COMPARE", "", false);
        List<Candidate> skt = java.util.stream.IntStream.range(0, 45)
                .mapToObj(i -> candidate("benefit:" + (100 + i) + ":policy:" + (200 + i) + ":tier:" + (300 + i),
                        100L + i, 200L + i, 300L + i)).toList();
        skt.forEach(candidate -> candidate.setCarrier("SKT"));
        Candidate kt = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        Candidate lgu = candidate("benefit:12:policy:22:tier:32", 12L, 22L, 32L);
        kt.setCarrier("KT");
        lgu.setCarrier("LGU");
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.SKT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(skt.subList(0, 40), true));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any())).thenReturn(new ChatSearchResult(List.of(kt), true));
        when(search.queryChat(eq(Carrier.LGU), any(), any(), anyString(), eq(40), any())).thenReturn(new ChatSearchResult(List.of(lgu), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") List<Candidate> found = call.getArgument(2);
            return new BenefitChatAnswerer.ChatAnswer("비교", found.stream().limit(8).map(Candidate::getCandidateKey).toList(), List.of());
        });

        var result = service.chat(request, null);

        assertThat(result.partners()).extracting("carrier").contains("SKT", "KT", "LGU");
    }

    @Test
    void ordinalFollowUpSelectsOnlyTheRequestedPreviousCandidate() {
        Candidate first = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        Candidate second = candidate("benefit:12:policy:22:tier:32", 12L, 22L, 32L);
        second.setPartnerId(10L);
        second.setPartnerName("다른 제휴처");
        BenefitChatRequest request = request("두 번째꺼 자세히", List.of(first.getCandidateKey(), second.getCandidateKey()));
        BenefitChatPlan plan = new BenefitChatPlan("영화 상세", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "DETAIL", "", true);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of(first, second));
        when(search.resolveChatCandidates(eq(request.previousCandidateKeys()), isNull(), isNull())).thenReturn(List.of(first, second));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") List<Candidate> found = call.getArgument(2);
            return new BenefitChatAnswerer.ChatAnswer("상세 안내", found.stream().map(Candidate::getCandidateKey).toList(), List.of());
        });

        var result = service.chat(request, null);

        assertThat(result.partners()).extracting("candidateKey").containsExactly(second.getCandidateKey());
    }

    @Test
    void ordinalBrandWithMultipleCarriersAsksWhichCarrierUsingBrandSpecificChoices() {
        Candidate kt = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        Candidate skt = candidate("benefit:12:policy:22:tier:32", 12L, 22L, 32L);
        kt.setPartnerName("CGV");
        skt.setPartnerName("CGV");
        skt.setCarrier("SKT");
        BenefitChatRequest request = request("첫 번째 혜택 자세히", List.of(kt.getCandidateKey(), skt.getCandidateKey()));
        BenefitChatPlan plan = new BenefitChatPlan("CGV 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT, Carrier.SKT, Carrier.LGU), null, true, "ANY", false, "", "DETAIL", "", true);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), isNull())).thenReturn(List.of(kt));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.SKT), isNull())).thenReturn(List.of(skt));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.LGU), isNull())).thenReturn(List.of());
        when(search.resolveChatCandidates(eq(request.previousCandidateKeys()), isNull(), isNull())).thenReturn(List.of(kt, skt));

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("CLARIFICATION");
        assertThat(result.partners()).isEmpty();
        assertThat(result.suggestedQuestions()).containsExactly(
                "CGV KT 혜택 이용 조건 알려줘", "CGV SKT 혜택 이용 조건 알려줘");
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void ambiguousDetailFollowUpAsksWhichPriorBenefit() {
        Candidate first = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        Candidate second = candidate("benefit:12:policy:22:tier:32", 12L, 22L, 32L);
        second.setPartnerId(10L);
        second.setPartnerName("다른 제휴처");
        BenefitChatRequest request = request("더 자세히 알려줘", List.of(first.getCandidateKey(), second.getCandidateKey()));
        BenefitChatPlan plan = new BenefitChatPlan("영화 상세", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "DETAIL", "", true);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of(first, second));

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("CLARIFICATION");
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void expiredFirstKeyDoesNotShiftSecondBrandIntoItsOrdinalSlot() {
        Candidate second = candidate("benefit:12:policy:22:tier:32", 12L, 22L, 32L);
        String expired = "benefit:11:policy:21:tier:31";
        BenefitChatPlan plan = new BenefitChatPlan("영화 상세", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "DETAIL", "", true);
        when(planner.plan(any(), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of(second));
        when(search.resolveChatCandidates(eq(List.of(expired, second.getCandidateKey())), isNull(), isNull())).thenReturn(List.of(second));
        when(answerer.answer(any(), eq(plan), anyList())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") List<Candidate> found = call.getArgument(2);
            return new BenefitChatAnswerer.ChatAnswer("상세 안내", found.stream().map(Candidate::getCandidateKey).toList(), List.of());
        });

        BenefitChatRequest firstRequest = request("첫 번째 혜택 자세히", List.of(expired, second.getCandidateKey()));
        BenefitChatRequest secondRequest = request("두 번째 혜택 자세히", List.of(expired, second.getCandidateKey()));

        assertThat(service.chat(firstRequest, null).status()).isEqualTo("CLARIFICATION");
        assertThat(service.chat(secondRequest, null).status()).isEqualTo("CLARIFICATION");
    }

    @Test
    void detailFollowUpWithOnlyExpiredKeysDoesNotSearchForAnUnrelatedBenefit() {
        BenefitChatRequest request = request("그 혜택 자세히", List.of("benefit:11:policy:21:tier:31"));
        BenefitChatPlan plan = new BenefitChatPlan("영화 상세", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "DETAIL", "", true);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of());

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("CLARIFICATION");
        verify(search, never()).queryChat(any(), any(), any(), anyString(), eq(40), any());
    }

    @Test
    void unknownCarrierOverviewInterleavesPartnerBrandsBeforeTheAnswererLimit() {
        BenefitChatRequest request = request("영화 혜택 알려줘", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(),
                List.of(), null, true, "ANY", false, "", "COMPARE", "", false);
        List<Candidate> samePartner = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> candidate("benefit:" + (100 + i) + ":policy:" + (200 + i) + ":tier:" + (300 + i),
                        100L + i, 200L + i, 300L + i)).toList();
        samePartner.forEach(candidate -> candidate.setPartnerId(9L));
        Candidate anotherBrand = candidate("benefit:999:policy:998:tier:997", 999L, 998L, 997L);
        anotherBrand.setPartnerId(99L);
        anotherBrand.setCarrier("LGU");
        List<Candidate> results = new java.util.ArrayList<>(samePartner);
        results.add(anotherBrand);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(null), any(), any(), anyString(), eq(40), any())).thenReturn(new ChatSearchResult(results, true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") List<Candidate> found = call.getArgument(2);
            return new BenefitChatAnswerer.ChatAnswer("혜택 안내", found.stream().limit(8).map(Candidate::getCandidateKey).toList(), List.of());
        });

        var result = service.chat(request, null);

        assertThat(result.partners()).extracting("partnerId").contains(9L, 99L);
    }

    @Test
    void validEmptyModelSelectionReturnsNoResultsWithoutSyntheticPartners() {
        BenefitChatRequest request = request("희귀한 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("희귀한 혜택", List.of(), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "SEARCH", "", false);
        Candidate evidence = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(any(), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(evidence), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("조건에 맞는 혜택을 확인하지 못했어요.", List.of(), List.of()));

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("NO_RESULTS");
        assertThat(result.partners()).isEmpty();
    }

    @Test
    void explicitOnlineBrowseReturnsEvidenceAndDoesNotCallSemanticSearch() {
        BenefitChatRequest request = request("온라인 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("온라인 혜택", List.of("멤버십 혜택"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ONLINE", false, "", "SEARCH", "", false);
        Candidate online = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        online.setUsageType("ONLINE");
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(search.browseChatCandidates(eq(Carrier.KT), eq(Grade.KT_GOLD), eq("ONLINE"), eq(40)))
                .thenReturn(List.of(online));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("온라인 이용 혜택입니다.", List.of(online.getCandidateKey()), List.of()));

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("ANSWER");
        assertThat(result.partners()).singleElement().satisfies(partner -> assertThat(partner.usageType()).isEqualTo("ONLINE"));
        verify(embedding, never()).embedQuery(anyString());
        verify(search, never()).queryChat(any(), any(), any(), anyString(), eq(40), any());
    }

    @Test
    void composedPlannerAndServiceTreatOnlineBenefitsAsBrowseIntent() {
        BenefitChatRequest request = new BenefitChatRequest("온라인 혜택 알려줘", List.of(), List.of(), null, null,
                Carrier.KT, null);
        BenefitChatPlanner heuristicPlanner = new BenefitChatPlanner(null, new com.fasterxml.jackson.databind.ObjectMapper(),
                "", java.time.Duration.ofSeconds(1), false);
        BenefitChatService composed = new BenefitChatService(heuristicPlanner, answerer, embedding, search,
                org.mockito.Mockito.mock(UserRepository.class), org.mockito.Mockito.mock(StoreService.class),
                org.mockito.Mockito.mock(com.itplace.userapi.benefit.service.BenefitOfferMatcher.class),
                org.mockito.Mockito.mock(com.itplace.userapi.reference.ReferenceDataService.class));
        ReflectionTestUtils.setField(composed, "aiEnabled", false);
        Candidate online = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        online.setUsageType("ONLINE");
        when(search.browseChatCandidates(eq(Carrier.KT), isNull(), eq("ONLINE"), eq(40))).thenReturn(List.of(online));
        when(answerer.answer(eq(request), any(), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("온라인 혜택입니다.", List.of(online.getCandidateKey()), List.of()));

        var result = composed.chat(request, null);

        assertThat(result.status()).isEqualTo("ANSWER");
        assertThat(result.partners()).singleElement().satisfies(partner -> assertThat(partner.usageType()).isEqualTo("ONLINE"));
        verify(search).browseChatCandidates(eq(Carrier.KT), isNull(), eq("ONLINE"), eq(40));
        verify(embedding, never()).embedQuery(anyString());
        verify(search, never()).queryChat(any(), any(), any(), anyString(), eq(40), any());
    }

    @Test
    void anyChannelChatReportsSelectedOfferChannelInsteadOfParentBoth() {
        BenefitChatRequest request = request("영화 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of(), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_VIP, false, "ANY", false, "", "SEARCH", "", false);
        var reference = org.mockito.Mockito.mock(ReferenceDataService.class);
        when(reference.gradesForProfile(Carrier.KT, Grade.KT_VIP)).thenReturn(List.of(Grade.KT_VIP));
        when(reference.resolveGradeAlias(Carrier.KT, "KT_VIP")).thenReturn(null);
        when(reference.isBenefitGrade(Carrier.KT, Grade.KT_VIP)).thenReturn(true);
        var scoped = new BenefitChatService(planner, answerer, embedding, search,
                org.mockito.Mockito.mock(UserRepository.class), org.mockito.Mockito.mock(StoreService.class),
                new BenefitOfferMatcher(reference), reference);
        ReflectionTestUtils.setField(scoped, "aiEnabled", false);
        Candidate candidate = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        candidate.setUsageType("BOTH");
        candidate.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("movie-vip", "온라인 영화표 할인", "온라인 예매", UsageType.ONLINE,
                                List.of("KT_VIP"), false, List.of("영화표"), List.of(), List.of(), List.of(), List.of(), List.of()),
                        new BenefitOffer("snack-general", "오프라인 매점 할인", "현장 매점", UsageType.OFFLINE,
                                List.of("KT_GENERAL"), false, List.of("매점 상품"), List.of(), List.of(), List.of(), List.of(), List.of())),
                OfferResolution.SCOPED, null));
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(search.queryChat(eq(Carrier.KT), eq(Grade.KT_VIP), isNull(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(candidate), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenAnswer(invocation -> {
            Candidate evidence = ((List<Candidate>) invocation.getArgument(2)).get(0);
            assertThat(evidence.getConditions().offers()).extracting(BenefitOffer::key).containsExactly("movie-vip");
            assertThat(evidence.getUsageType()).isEqualTo("ONLINE");
            assertThat(evidence.getManual()).contains("ONLINE:").doesNotContain("매점 상품");
            return new BenefitChatAnswerer.ChatAnswer("영화 혜택", List.of(candidate.getCandidateKey()), List.of());
        });

        var result = scoped.chat(request, null);

        assertThat(result.partners()).singleElement().satisfies(partner -> {
            assertThat(partner.usageType()).isEqualTo("ONLINE");
            assertThat(partner.offers()).extracting(BenefitOffer::key).containsExactly("movie-vip");
            assertThat(partner.benefitText()).doesNotContain("매점 상품");
        });
    }

    @Test
    void detailAmountQuestionKeepsScopedStructuredFactForAnswerer() {
        BenefitChatRequest request = request("LG유플러스 우수 등급인데 이마트24 할인은 얼마야?", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("이마트24 편의점 할인 금액", List.of("편의점"), List.of("이마트24"), List.of(),
                List.of(Carrier.LGU), Grade.BASIC, false, "ANY", false, "", "DETAIL", "", false);
        ReferenceDataService reference = org.mockito.Mockito.mock(ReferenceDataService.class);
        when(reference.gradesForProfile(Carrier.LGU, Grade.BASIC)).thenReturn(List.of(Grade.BASIC));
        when(reference.isBenefitGrade(Carrier.LGU, Grade.BASIC)).thenReturn(true);
        when(reference.resolveGradeAlias(Carrier.LGU, "BASIC")).thenReturn(null);
        BenefitOffer offer = new BenefitOffer("offline-discount", "이마트24 할인", "", UsageType.OFFLINE,
                List.of("BASIC"), false, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("DISCOUNT_AMOUNT", new BigDecimal("50"), "KRW", null,
                                new BigDecimal("1000"), "KRW", null, null, "1,000원당 50원 할인", null),
                        new BenefitFact("USAGE_LIMIT", new BigDecimal("1"), "COUNT", "DAY", null,
                                null, null, null, "일 1회", null),
                        new BenefitFact("SPEND_CAP", new BigDecimal("200000"), "KRW", null,
                                null, null, null, "MAX", "최대 20만원", null)));
        Candidate candidate = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        candidate.setPartnerName("이마트24");
        candidate.setBusinessType("CONVENIENCE_STORE");
        candidate.setCarrier("LGU");
        candidate.setGrade("BASIC");
        candidate.setUsageType("OFFLINE");
        candidate.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(offer), OfferResolution.SINGLE, null));
        BenefitChatService scoped = new BenefitChatService(planner, answerer, embedding, search,
                org.mockito.Mockito.mock(UserRepository.class), org.mockito.Mockito.mock(StoreService.class),
                new BenefitOfferMatcher(reference), reference);
        ReflectionTestUtils.setField(scoped, "aiEnabled", true);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.LGU), eq(Grade.BASIC), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(candidate), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenAnswer(call -> {
            @SuppressWarnings("unchecked") List<Candidate> evidence = call.getArgument(2);
            assertThat(evidence).singleElement().satisfies(item -> {
                assertThat(item.getConditions().offers()).singleElement().satisfies(scopedOffer -> {
                    assertThat(scopedOffer.usageType()).isEqualTo(UsageType.OFFLINE);
                    assertThat(scopedOffer.gradeCodes()).containsExactly("BASIC");
                    assertThat(scopedOffer.facts()).extracting(BenefitFact::type)
                            .containsExactly("DISCOUNT_AMOUNT", "USAGE_LIMIT", "SPEND_CAP");
                    assertThat(scopedOffer.facts()).first().satisfies(fact -> {
                        assertThat(fact.value()).isEqualByComparingTo("50");
                        assertThat(fact.basisAmount()).isEqualByComparingTo("1000");
                    });
                });
            });
            return new BenefitChatAnswerer.ChatAnswer("이마트24는 1,000원당 50원 할인돼요.",
                    List.of(candidate.getCandidateKey()), List.of());
        });

        var result = scoped.chat(request, null);

        assertThat(result.reason()).contains("50원");
        assertThat(result.partners()).singleElement().satisfies(partner ->
                assertThat(partner.offers().get(0).facts()).first().satisfies(fact ->
                        assertThat(fact.basisAmount()).isEqualByComparingTo("1000")));
    }

    @Test
    void offlineChatKeepsOnlyGradeAndChannelMatchingOfferInEvidenceAndCard() {
        BenefitChatRequest request = request("오프라인 매점 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("오프라인 매점 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GENERAL, false, "OFFLINE", false, "", "SEARCH", "", false);
        var reference = org.mockito.Mockito.mock(ReferenceDataService.class);
        when(reference.gradesForProfile(Carrier.KT, Grade.KT_GENERAL)).thenReturn(List.of(Grade.KT_GENERAL));
        when(reference.resolveGradeAlias(Carrier.KT, "KT_GENERAL")).thenReturn(null);
        when(reference.isBenefitGrade(Carrier.KT, Grade.KT_GENERAL)).thenReturn(true);
        BenefitOfferMatcher matcher = new BenefitOfferMatcher(reference);
        BenefitChatService scoped = new BenefitChatService(planner, answerer, embedding, search,
                org.mockito.Mockito.mock(UserRepository.class), org.mockito.Mockito.mock(StoreService.class), matcher, reference);
        when(reference.gradesForProfile(Carrier.KT, Grade.KT_GENERAL)).thenReturn(List.of(Grade.KT_GENERAL));
        when(reference.isBenefitGrade(Carrier.KT, Grade.KT_GENERAL)).thenReturn(true);
        ReflectionTestUtils.setField(scoped, "aiEnabled", false);
        Candidate candidate = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        candidate.setUsageType("BOTH");
        candidate.setContext("온라인 예매 티켓 할인 / 오프라인 매점 할인");
        candidate.setManual("온라인 티켓, 오프라인 매점 1일 1회");
        candidate.setConditions(new BenefitConditions(List.of("혼합 원문"), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("movie-vip", "온라인 영화표 할인", "온라인 예매", UsageType.ONLINE,
                                List.of("KT_VIP"), false, List.of("영화표"), List.of(), List.of(), List.of(), List.of(), List.of()),
                        new BenefitOffer("snack-general", "오프라인 매점 할인", "현장 매점", UsageType.OFFLINE,
                                List.of("KT_GENERAL"), false, List.of("매점 상품"), List.of(), List.of("1일 1회"), List.of(), List.of(), List.of())),
                OfferResolution.SCOPED, null));
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(search.queryChat(eq(Carrier.KT), eq(Grade.KT_GENERAL), isNull(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(candidate), true));
        var result = scoped.chat(request, null);

        assertThat(result.partners()).singleElement().satisfies(partner -> {
            assertThat(partner.usageType()).isEqualTo("OFFLINE");
            assertThat(partner.offers()).extracting(BenefitOffer::key).containsExactly("snack-general");
            assertThat(partner.benefitText()).doesNotContain("영화표");
        });
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void genericNearbyBrowseWithoutGpsReturnsBenefitsAndAsksForLocation() {
        BenefitChatRequest request = request("근처 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("근처 혜택", List.of("멤버십 혜택"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", true, "", "SEARCH", "", false);
        Candidate candidate = candidate("benefit:11:policy:21:tier:31", 11L, 21L, 31L);
        candidate.setContext("OFFLINE: [할인형] / UNKNOWN: 이용처 안내");
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(search.browseChatCandidates(eq(Carrier.KT), eq(Grade.KT_GOLD), eq("ANY"), eq(40)))
                .thenReturn(List.of(candidate));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("확인된 혜택입니다.", List.of(candidate.getCandidateKey()), List.of()));

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("ANSWER");
        assertThat(result.suggestedQuestions()).contains("내 주변 매장 찾아줘");
        assertThat(result.reason()).contains("현재 위치 접근을 허용하거나 지도에서 지역을 검색");
        assertThat(result.partners()).singleElement().satisfies(partner -> {
            assertThat(partner.representativeStoreId()).isNull();
            assertThat(partner.benefitText()).contains("할인형", "이용처 안내")
                    .doesNotContain("OFFLINE:", "UNKNOWN:");
        });
    }

    @Test
    void structuredCategorySearchReturnsRankedDistinctCardsWithoutCallingAnswerer() {
        BenefitChatRequest request = request("영화 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate first = candidate("first", 1L, 1L, 1L);
        first.setPartnerId(10L);
        first.setPartnerName("CGV");
        Candidate duplicate = candidate("duplicate", 2L, 2L, 2L);
        duplicate.setPartnerId(10L);
        duplicate.setPartnerName("CGV");
        Candidate second = candidate("second", 3L, 3L, 3L);
        second.setPartnerId(11L);
        second.setPartnerName("롯데시네마");
        Candidate unrelated = Candidate.builder().candidateKey("unrelated").benefitId(4L).policyId(4L)
                .partnerId(12L).partnerName("카페").businessType("BEVERAGE_CAFE").build();
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(first, duplicate, second, unrelated), true));

        var result = service.chat(request, null);

        assertThat(result.status()).isEqualTo("ANSWER");
        assertThat(result.partners()).extracting("candidateKey").containsExactly("first", "second");
        assertThat(result.reason()).contains("영화 혜택");
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void fastNearbySearchKeepsLocationDisclaimerAndSuggestion() {
        BenefitChatRequest request = request("영화 근처 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", true, "", "SEARCH", "", false);
        Candidate candidate = candidate("movie", 1L, 2L, 3L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(candidate), true));

        var result = service.chat(request, null);

        assertThat(result.reason()).contains("현재 위치 접근을 허용하거나 지도에서 지역을 검색");
        assertThat(result.suggestedQuestions()).contains("내 주변 매장 찾아줘");
    }

    @Test
    void vagueSearchStillUsesSemanticAnswerer() {
        BenefitChatRequest request = request("특이한 멤버십 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("특이한 멤버십 혜택", List.of(), List.of(), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        Candidate candidate = candidate("candidate", 1L, 1L, 1L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(candidate), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("후보를 검토한 답변", List.of(candidate.getCandidateKey()), List.of()));

        assertThat(service.chat(request, null).reason()).isEqualTo("후보를 검토한 답변");
        verify(answerer).answer(eq(request), eq(plan), anyList());
    }

    @Test
    void detailStreamsOnlyScopedPreviewBetweenSearchAndAnswering() {
        BenefitChatRequest request = request("이 혜택 자세히", List.of("candidate"));
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "DETAIL", "", true);
        Candidate candidate = candidate("candidate", 1L, 2L, 3L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.resolveChatCandidates(anyList(), eq(Carrier.KT), eq(Grade.KT_GOLD))).thenReturn(List.of(candidate));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("상세 답변", List.of(candidate.getCandidateKey()), List.of()));
        List<String> order = new java.util.ArrayList<>();
        List<BenefitChatResponse> previews = new java.util.ArrayList<>();

        var result = service.chat(request, null, (event, data) -> {
            if ("status".equals(event)) order.add((String) ((java.util.Map<?, ?>) data).get("stage"));
            else if ("cards".equals(event)) {
                order.add("cards");
                previews.add((BenefitChatResponse) data);
            }
        });

        assertThat(order).containsExactly("planning", "searching", "cards", "answering");
        assertThat(previews).singleElement().satisfies(preview -> {
            assertThat(preview.reason()).isEmpty();
            assertThat(preview.suggestedQuestions()).isEmpty();
            assertThat(preview.traceId()).isEqualTo(result.traceId());
            assertThat(preview.partners()).extracting("candidateKey").containsExactly("candidate");
        });
    }

    @Test
    void unfilteredFreeTextDetailDoesNotPreviewSemanticallyUnverifiedCandidates() {
        BenefitChatRequest request = request("스타벅스 혜택 자세히", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("스타벅스 혜택 자세히", List.of(), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "DETAIL", "", false);
        Candidate candidate = candidate("candidate", 1L, 2L, 3L);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), eq(Grade.KT_GOLD), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(candidate), true));
        when(answerer.answer(eq(request), eq(plan), anyList())).thenReturn(
                new BenefitChatAnswerer.ChatAnswer("모델 검토 후 답변", List.of(candidate.getCandidateKey()), List.of()));
        List<String> events = new java.util.ArrayList<>();

        var result = service.chat(request, null, (event, data) -> {
            if ("status".equals(event)) events.add((String) ((java.util.Map<?, ?>) data).get("stage"));
            else events.add(event);
        });

        assertThat(result.reason()).isEqualTo("모델 검토 후 답변");
        assertThat(events).containsExactly("planning", "searching", "answering");
    }

    @Test
    void noResultsNeverEmitsPreviewOrAnswering() {
        BenefitChatRequest request = request("영화 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), null, false, "ANY", false, "", "SEARCH", "", false);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(), true));
        List<String> events = new java.util.ArrayList<>();

        assertThat(service.chat(request, null, (event, data) -> events.add(event))).isNotNull();

        assertThat(events).containsExactly("status", "status");
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void clarificationAndUnsupportedGradeNeverEmitPreviewCards() {
        BenefitChatRequest request = request("영화 혜택", List.of());
        BenefitChatPlan clarify = new BenefitChatPlan("", List.of(), List.of(), List.of(), List.of(), null,
                false, "ANY", false, "", "CLARIFY", "조금 더 알려주세요.", false);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(clarify);
        List<String> events = new java.util.ArrayList<>();

        assertThat(service.chat(request, null, (event, data) -> events.add(event)).status()).isEqualTo("CLARIFICATION");
        assertThat(events).containsExactly("status");

        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(),
                List.of(Carrier.KT), Grade.KT_GOLD, false, "ANY", false, "", "SEARCH", "", false);
        Candidate ineligible = candidate("ineligible", 1L, 2L, 3L);
        ineligible.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("vip", "VIP 영화 혜택", "", UsageType.BOTH, List.of("KT_VIP"), false,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of())), OfferResolution.SCOPED, null));
        var matcher = org.mockito.Mockito.mock(BenefitOfferMatcher.class);
        when(matcher.hasKnownOffers(any())).thenReturn(true);
        when(matcher.supports(any(), any(), any(), anyList())).thenReturn(false);
        service = new BenefitChatService(planner, answerer, embedding, search,
                org.mockito.Mockito.mock(UserRepository.class), org.mockito.Mockito.mock(StoreService.class), matcher,
                org.mockito.Mockito.mock(ReferenceDataService.class));
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(ineligible), true));
        events.clear();

        assertThat(service.chat(request, null, (event, data) -> events.add(event)).status()).isEqualTo("NO_RESULTS");
        assertThat(events).containsExactly("status", "status");
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void missingCarrierClarifiesBeforeEmbeddingSearchOrPreview() {
        BenefitChatRequest request = request("영화 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(), List.of(), null,
                false, "ANY", false, "", "SEARCH", "", false);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        List<String> events = new java.util.ArrayList<>();

        var result = service.chat(request, null, (event, data) -> events.add(event));

        assertThat(result.status()).isEqualTo("CLARIFICATION");
        assertThat(result.partners()).isEmpty();
        assertThat(result.suggestedQuestions()).containsExactly("SKT", "KT", "LG U+", "전체 비교");
        assertThat(events).containsExactly("status");
        verify(embedding, never()).embedQuery(anyString());
        verify(search, never()).queryChat(any(), any(), any(), anyString(), eq(40), any());
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void gradeSpecificRetrievedOfferAsksUsingActiveSelectableReferenceLabels() {
        BenefitChatRequest request = request("영화 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(), List.of(Carrier.KT), null,
                false, "ANY", false, "", "SEARCH", "", false);
        Candidate candidate = candidate("graded", 1L, 2L, 3L);
        candidate.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("vip", "VIP 영화 혜택", "", UsageType.BOTH, List.of("KT_VIP"), false,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of())), OfferResolution.SCOPED, null));
        var references = org.mockito.Mockito.mock(ReferenceDataService.class);
        when(references.getReferenceData()).thenReturn(new com.itplace.userapi.reference.ReferenceData(List.of(
                new com.itplace.userapi.reference.ReferenceData.Grade("KT", "KT_VIP", "VIP", 1, true, true, true, false),
                new com.itplace.userapi.reference.ReferenceData.Grade("KT", "KT_BASIC", "일반", 2, true, false, true, false)),
                List.of(), List.of(), List.of()));
        service = new BenefitChatService(planner, answerer, embedding, search,
                org.mockito.Mockito.mock(UserRepository.class), org.mockito.Mockito.mock(StoreService.class),
                org.mockito.Mockito.mock(BenefitOfferMatcher.class), references);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(candidate), true));
        List<String> events = new java.util.ArrayList<>();

        var result = service.chat(request, null, (event, data) -> events.add(event));

        assertThat(result.status()).isEqualTo("CLARIFICATION");
        assertThat(result.partners()).isEmpty();
        assertThat(result.suggestedQuestions()).containsExactly("KT VIP 등급이야", "KT 등급은 모르겠어");
        assertThat(events).doesNotContain("cards", "answering");
        verify(answerer, never()).answer(any(), any(), anyList());
    }

    @Test
    void allGradeBenefitAndExplicitUnknownGradeDoNotAskAgain() {
        BenefitChatRequest request = request("영화 혜택", List.of());
        BenefitChatPlan knownCarrier = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(), List.of(Carrier.KT), null,
                false, "ANY", false, "", "SEARCH", "", false);
        Candidate allGrade = candidate("all-grade", 1L, 2L, 3L);
        allGrade.setAllGrade(true);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(knownCarrier);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(allGrade), true));

        assertThat(service.chat(request, null).status()).isEqualTo("ANSWER");

        BenefitChatPlan declined = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(), List.of(Carrier.KT), null,
                false, "ANY", false, "", "SEARCH", "", false, true);
        Candidate gradeSpecific = candidate("grade-specific", 4L, 5L, 6L);
        gradeSpecific.setAllGrade(false);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(declined);
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(gradeSpecific), true));

        assertThat(service.chat(request, null).status()).isEqualTo("ANSWER");

        Candidate tierSpecific = candidate("tier-specific", 7L, 8L, 9L);
        tierSpecific.setAllGrade(false);
        tierSpecific.setConditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("all", "공통 안내", "", UsageType.BOTH, List.of(), true,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of())), OfferResolution.SCOPED, null));
        var references = org.mockito.Mockito.mock(ReferenceDataService.class);
        when(references.getReferenceData()).thenReturn(new com.itplace.userapi.reference.ReferenceData(List.of(
                new com.itplace.userapi.reference.ReferenceData.Grade("KT", "KT_VIP", "VIP", 1, true, true, true, false)),
                List.of(), List.of(), List.of()));
        service = new BenefitChatService(planner, answerer, embedding, search,
                org.mockito.Mockito.mock(UserRepository.class), org.mockito.Mockito.mock(StoreService.class),
                org.mockito.Mockito.mock(BenefitOfferMatcher.class), references);
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(knownCarrier);
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(tierSpecific), true));

        var tierResult = service.chat(request, null);

        assertThat(tierResult.status()).isEqualTo("CLARIFICATION");
        assertThat(tierResult.suggestedQuestions()).contains("KT VIP 등급이야");
    }

    @Test
    void selectedGradeReplacesRepresentativeOnlyForVerifiedAllGradeCandidate() {
        BenefitChatRequest request = request("영화 혜택", List.of());
        BenefitChatPlan plan = new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(), List.of(Carrier.KT),
                Grade.KT_VIP, false, "ANY", false, "", "SEARCH", "", false);
        Candidate allGrade = candidate("all-grade-tier", 1L, 2L, 3L);
        allGrade.setGrade("KT_VVIP");
        allGrade.setAllGrade(true);
        Candidate restricted = candidate("restricted-tier", 4L, 5L, 6L);
        restricted.setGrade("KT_VVIP");
        restricted.setAllGrade(false);
        var references = org.mockito.Mockito.mock(ReferenceDataService.class);
        when(references.isSelectableProfile(Carrier.KT, Grade.KT_VIP)).thenReturn(true);
        service = new BenefitChatService(planner, answerer, embedding, search,
                org.mockito.Mockito.mock(UserRepository.class), org.mockito.Mockito.mock(StoreService.class),
                org.mockito.Mockito.mock(BenefitOfferMatcher.class), references);
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        when(planner.plan(eq(request), eq(null), eq(null))).thenReturn(plan);
        when(embedding.embedQuery(anyString())).thenReturn(List.of(0.1f));
        when(search.queryChat(eq(Carrier.KT), any(), any(), anyString(), eq(40), any()))
                .thenReturn(new ChatSearchResult(List.of(allGrade), true))
                .thenReturn(new ChatSearchResult(List.of(restricted), true));

        var allGradeResult = service.chat(request, null);
        var restrictedResult = service.chat(request, null);

        assertThat(allGradeResult.partners()).singleElement().satisfies(partner -> {
            assertThat(partner.grade()).isEqualTo("KT_VIP");
            assertThat(partner.candidateKey()).isEqualTo(allGrade.getCandidateKey());
        });
        assertThat(restrictedResult.partners()).singleElement().satisfies(partner -> {
            assertThat(partner.grade()).isEqualTo("KT_VVIP");
            assertThat(partner.candidateKey()).isEqualTo(restricted.getCandidateKey());
        });
    }


    private static Candidate candidate(String key, Long benefitId, Long policyId, Long tierId) {
        return Candidate.builder().candidateKey(key).benefitId(benefitId).policyId(policyId).tierBenefitId(tierId)
                .partnerId(9L).partnerName("제휴처").benefitName("멤버십 혜택").carrier("KT").grade("KT_GOLD")
                .businessType("MOVIE_THEATER").category("영화")
                .context("안내 원문").description("혜택 안내").build();
    }

    private static BenefitChatRequest request(String question, List<String> keys) {
        return new BenefitChatRequest(question, List.of(), keys, null, null, null, null);
    }

    private static BenefitChatPlan plan(String mode, boolean compare, Carrier carrier) {
        return new BenefitChatPlan("영화 혜택", List.of("영화"), List.of(), List.of(), List.of(carrier), Grade.KT_GOLD,
                compare, "ANY", false, "", mode, "", false);
    }
}
