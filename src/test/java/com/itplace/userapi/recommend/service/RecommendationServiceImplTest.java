package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.itplace.userapi.log.repository.LogRepository;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import com.itplace.userapi.recommend.service.RecommendationPersistenceService.CachedBatch;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceImplTest {

    @Mock
    private UserFeatureService userFeatureService;

    @Mock
    private OpenAIService aiService;

    @Mock
    private LogRepository logRepository;

    @Mock
    private RecommendationPersistenceService persistenceService;

    @Mock
    private RecommendationGenerationLock generationLock;

    @Mock
    private RecommendationTraceRecorder traceRecorder;

    @InjectMocks
    private RecommendationServiceImpl recommendationService;

    @Test
    void recommend_generatesAndPersistsAfterCacheMiss() {
        UserFeature userFeature = userFeature();
        Candidate candidate = Candidate.builder().benefitId(1L).partnerName("파트너").build();
        Recommendations generated = recommendation("파트너", "es_vector");

        when(persistenceService.findLatestActiveBatch(eq(7L), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(generationLock.acquire(7L)).thenReturn(() -> { });
        when(userFeatureService.loadUserFeature(7L)).thenReturn(userFeature);
        when(aiService.vectorSearch(userFeature, 10)).thenReturn(List.of(candidate));
        when(aiService.rerankAndExplain(userFeature, List.of(candidate), 3)).thenReturn(List.of(generated));

        List<Recommendations> result = recommendationService.recommend(7L, 3);

        assertThat(result).containsExactly(generated);
        assertThat(generated.getRequestId()).startsWith("rec-req-7-");
        assertThat(generated.getImpressionId()).startsWith("rec-imp-7-1-");
        assertThat(generated.getAlgorithmVersion()).isEqualTo("personalized-es-quality-v1");
        assertThat(generated.getFallbackFlags()).isEmpty();
        verify(persistenceService).replaceActiveBatch(7L, List.of(generated), "personalized-es-quality-v1");
        verify(traceRecorder).recordGenerated(
                eq(7L),
                argThat(requestId -> requestId.startsWith("rec-req-7-")),
                eq("personalized-es-quality-v1"),
                eq(List.of(candidate)),
                eq(result),
                argThat(latency -> latency.containsKey("total")),
                eq("miss"),
                eq("expired_or_absent")
        );
    }

    @Test
    void recommend_returnsLatestBatchWithoutAcquiringLockWhenCacheIsValid() {
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(10);
        Recommendations cached = recommendation("최신파트너", "es_vector");
        when(persistenceService.findLatestActiveBatch(eq(7L), any(LocalDateTime.class)))
                .thenReturn(Optional.of(new CachedBatch(createdAt, List.of(cached))));
        when(logRepository.findLatestLoggingAtByEvents(eq(7L), anyList())).thenReturn(Optional.empty());
        when(persistenceService.hasPersistentInvalidatingSignalAfter(7L, createdAt)).thenReturn(false);

        List<Recommendations> result = recommendationService.recommend(7L, 3);

        assertThat(result)
                .singleElement()
                .satisfies(recommendation -> {
                    assertThat(recommendation.getPartnerName()).isEqualTo("최신파트너");
                    assertThat(recommendation.getRequestId()).startsWith("rec-req-7-");
                    assertThat(recommendation.getCandidateSource()).isEqualTo("es_vector");
                    assertThat(recommendation.getFallbackFlags()).containsExactly("cached_recommendation");
                });
        verifyNoInteractions(generationLock, userFeatureService, aiService);
        verify(traceRecorder).recordCached(
                eq(7L),
                argThat(requestId -> requestId.startsWith("rec-req-7-")),
                eq("personalized-es-quality-v1"),
                eq(result),
                argThat(latency -> latency.containsKey("total")),
                eq("none")
        );
    }

    @Test
    void recommend_rechecksCacheAfterAcquiringLock() {
        LocalDateTime createdAt = LocalDateTime.now();
        Recommendations cached = recommendation("먼저 생성된 파트너", "es_vector");
        when(persistenceService.findLatestActiveBatch(eq(7L), any(LocalDateTime.class)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new CachedBatch(createdAt, List.of(cached))));
        when(generationLock.acquire(7L)).thenReturn(() -> { });
        when(logRepository.findLatestLoggingAtByEvents(eq(7L), anyList())).thenReturn(Optional.empty());
        when(persistenceService.hasPersistentInvalidatingSignalAfter(7L, createdAt)).thenReturn(false);

        List<Recommendations> result = recommendationService.recommend(7L, 3);

        assertThat(result).singleElement()
                .extracting(Recommendations::getPartnerName)
                .isEqualTo("먼저 생성된 파트너");
        verifyNoInteractions(userFeatureService, aiService);
        verify(persistenceService, never()).replaceActiveBatch(anyLong(), anyList(), any());
        verify(traceRecorder).recordCached(eq(7L), any(), any(), eq(result), any(), eq("none"));
    }

    @Test
    void recommend_releasesLockWhenGenerationFails() {
        RecommendationGenerationLock.Lease lease = org.mockito.Mockito.mock(RecommendationGenerationLock.Lease.class);
        UserFeature userFeature = userFeature();
        when(persistenceService.findLatestActiveBatch(eq(7L), any(LocalDateTime.class)))
                .thenReturn(Optional.empty());
        when(generationLock.acquire(7L)).thenReturn(lease);
        when(userFeatureService.loadUserFeature(7L)).thenReturn(userFeature);
        when(aiService.vectorSearch(userFeature, 10)).thenThrow(new IllegalStateException("embedding failed"));

        assertThatThrownBy(() -> recommendationService.recommend(7L, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding failed");

        verify(lease).close();
        verify(persistenceService, never()).replaceActiveBatch(anyLong(), anyList(), any());
    }

    @Test
    void recommend_allowsOnlyOneGenerationForConcurrentRequests() throws Exception {
        AtomicReference<CachedBatch> storedBatch = new AtomicReference<>();
        AtomicInteger cacheLookupCount = new AtomicInteger();
        CountDownLatch firstGenerationStarted = new CountDownLatch(1);
        CountDownLatch secondInitialLookupCompleted = new CountDownLatch(1);
        CountDownLatch allowFirstGeneration = new CountDownLatch(1);
        ReentrantLock userLock = new ReentrantLock();
        RecommendationGenerationLock singleFlightLock = userId -> {
            userLock.lock();
            return userLock::unlock;
        };
        RecommendationServiceImpl concurrentService = new RecommendationServiceImpl(
                userFeatureService,
                aiService,
                logRepository,
                persistenceService,
                singleFlightLock,
                traceRecorder
        );
        UserFeature userFeature = userFeature();
        Candidate candidate = Candidate.builder().benefitId(1L).partnerName("파트너").build();
        Recommendations generated = recommendation("파트너", "es_vector");

        when(persistenceService.findLatestActiveBatch(eq(7L), any(LocalDateTime.class))).thenAnswer(invocation -> {
            if (cacheLookupCount.incrementAndGet() >= 3) {
                secondInitialLookupCompleted.countDown();
            }
            return Optional.ofNullable(storedBatch.get());
        });
        when(logRepository.findLatestLoggingAtByEvents(eq(7L), anyList())).thenReturn(Optional.empty());
        when(persistenceService.hasPersistentInvalidatingSignalAfter(eq(7L), any(LocalDateTime.class)))
                .thenReturn(false);
        when(userFeatureService.loadUserFeature(7L)).thenReturn(userFeature);
        when(aiService.vectorSearch(userFeature, 10)).thenAnswer(invocation -> {
            firstGenerationStarted.countDown();
            assertThat(allowFirstGeneration.await(2, TimeUnit.SECONDS)).isTrue();
            return List.of(candidate);
        });
        when(aiService.rerankAndExplain(userFeature, List.of(candidate), 3)).thenReturn(List.of(generated));
        org.mockito.Mockito.doAnswer(invocation -> {
            List<Recommendations> saved = invocation.getArgument(1);
            Recommendations persisted = recommendation(saved.get(0).getPartnerName(), saved.get(0).getCandidateSource());
            storedBatch.set(new CachedBatch(LocalDateTime.now(), List.of(persisted)));
            return null;
        }).when(persistenceService).replaceActiveBatch(eq(7L), anyList(), eq("personalized-es-quality-v1"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Recommendations>> first = executor.submit(() -> concurrentService.recommend(7L, 3));
            assertThat(firstGenerationStarted.await(2, TimeUnit.SECONDS)).isTrue();
            Future<List<Recommendations>> second = executor.submit(() -> concurrentService.recommend(7L, 3));
            assertThat(secondInitialLookupCompleted.await(2, TimeUnit.SECONDS)).isTrue();

            allowFirstGeneration.countDown();
            List<Recommendations> firstResult = first.get(3, TimeUnit.SECONDS);
            List<Recommendations> secondResult = second.get(3, TimeUnit.SECONDS);

            assertThat(firstResult).singleElement();
            assertThat(secondResult).singleElement();
            assertThat(List.of(firstResult.get(0), secondResult.get(0)))
                    .anySatisfy(result -> assertThat(result.getFallbackFlags()).containsExactly("cached_recommendation"));
            verify(aiService, times(1)).vectorSearch(userFeature, 10);
            verify(aiService, times(1)).rerankAndExplain(userFeature, List.of(candidate), 3);
            verify(persistenceService, times(1))
                    .replaceActiveBatch(eq(7L), anyList(), eq("personalized-es-quality-v1"));
            verify(traceRecorder, times(1)).recordGenerated(anyLong(), any(), any(), anyList(), anyList(), any(), any(), any());
            verify(traceRecorder, times(1)).recordCached(anyLong(), any(), any(), anyList(), any(), any());
        } finally {
            allowFirstGeneration.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void hasInvalidatingSignalAfter_returnsTrueWhenRecentBehaviorLogExists() {
        LocalDateTime latestRecommendationDate = LocalDateTime.now().minusHours(2);
        Instant recentEventAt = latestRecommendationDate.plusMinutes(10)
                .atZone(ZoneId.systemDefault())
                .toInstant();
        when(logRepository.findLatestLoggingAtByEvents(eq(7L), anyList())).thenReturn(Optional.of(recentEventAt));

        assertThat(recommendationService.hasInvalidatingSignalAfter(7L, latestRecommendationDate)).isTrue();
        verify(persistenceService, never()).hasPersistentInvalidatingSignalAfter(anyLong(), any());
    }

    @Test
    void hasInvalidatingSignalAfter_checksPersistentSignalsWhenLogsAreUnchanged() {
        LocalDateTime latestRecommendationDate = LocalDateTime.now().minusHours(2);
        when(logRepository.findLatestLoggingAtByEvents(eq(7L), anyList())).thenReturn(Optional.empty());
        when(persistenceService.hasPersistentInvalidatingSignalAfter(7L, latestRecommendationDate)).thenReturn(true);

        assertThat(recommendationService.hasInvalidatingSignalAfter(7L, latestRecommendationDate)).isTrue();
    }

    @Test
    void recommend_orchestrationDoesNotOpenDatabaseTransaction() throws Exception {
        assertThat(RecommendationServiceImpl.class
                .getMethod("recommend", Long.class, int.class)
                .isAnnotationPresent(Transactional.class))
                .isFalse();
    }

    private UserFeature userFeature() {
        return UserFeature.builder()
                .userId(7L)
                .topCategories(List.of())
                .recentPartnerNames(List.of())
                .clickPartners(List.of())
                .searchPartners(List.of())
                .detailPartners(List.of())
                .build();
    }

    private Recommendations recommendation(String partnerName, String candidateSource) {
        return Recommendations.builder()
                .rank(1)
                .partnerName(partnerName)
                .reason("추천 이유")
                .imgUrl("image")
                .benefitIds(List.of(1L))
                .candidateSource(candidateSource)
                .build();
    }
}
