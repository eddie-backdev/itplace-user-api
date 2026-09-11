package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.common.redis.CacheConfig;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.partner.entity.Partner;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.util.ByteUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@ExtendWith(MockitoExtension.class)
class PartnerBenefitCacheServiceTest {

    @Test
    void redisBatchDecodesExistingPayloadsOnCallerAfterDispatchingAllReads() throws Exception {
        var originalManager = (RedisCacheManager) new CacheConfig().cacheManager(mock(RedisConnectionFactory.class));
        originalManager.afterPropertiesSet();
        var originalConfig = originalManager.getCacheConfigurations().get("partner-benefits");
        var originalValues = originalConfig.getValueSerializationPair();
        List<Thread> decodingThreads = new ArrayList<>();
        var values = SerializationPair.fromSerializer(
                new RedisSerializer<Object>() {
                    @Override public byte[] serialize(Object value) {
                        return ByteUtils.getBytes(originalValues.write(value));
                    }
                    @Override public Object deserialize(byte[] value) {
                        decodingThreads.add(Thread.currentThread());
                        return originalValues.read(ByteBuffer.wrap(value));
                    }
                });
        var writer = mock(RedisCacheWriter.class);
        var manager = RedisCacheManager.builder(writer)
                .withInitialCacheConfigurations(Map.of("partner-benefits",
                        originalConfig.prefixCacheNameWith("test:").serializeValuesWith(values)))
                .build();
        manager.afterPropertiesSet();
        var service = new PartnerBenefitCacheService(benefitRepository, benefitCarrierPolicyRepository,
                carrierTierBenefitRepository, manager);
        var pending = new ConcurrentHashMap<String, CompletableFuture<byte[]>>();
        var dispatched = new CountDownLatch(3);
        when(writer.retrieve(org.mockito.ArgumentMatchers.eq("partner-benefits"),
                org.mockito.ArgumentMatchers.any(byte[].class))).thenAnswer(call -> {
                    var future = new CompletableFuture<byte[]>().orTimeout(5, TimeUnit.SECONDS);
                    pending.put(new String(call.getArgument(1), StandardCharsets.UTF_8), future);
                    dispatched.countDown();
                    return future;
                });
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(3L))).thenReturn(List.of());
        var expected = new ArrayList<>(List.of(new BenefitCacheDto(9L,"기존 캐시",new ArrayList<>())));
        byte[] cached = ByteUtils.getBytes(originalValues.write(expected));
        byte[] empty = ByteUtils.getBytes(originalValues.write(new ArrayList<>()));
        var eventLoop = Executors.newSingleThreadExecutor(
                task -> new Thread(task,"test-redis-event-loop"));
        try {
            var completion = eventLoop.submit(() -> {
                assertThat(dispatched.await(5,TimeUnit.SECONDS)).isTrue();
                pending.get("test:partner-benefits::1").complete(cached);
                pending.get("test:partner-benefits::2").complete(empty);
                pending.get("test:partner-benefits::3").complete(null);
                return null;
            });
            var result = service.getBenefitsBatch(List.of(1L,2L,3L,1L));
            completion.get(5,TimeUnit.SECONDS);
            assertThat(result.get(1L)).usingRecursiveComparison().isEqualTo(expected);
            assertThat(result.get(2L)).isEmpty();
            assertThat(result.get(3L)).isEmpty();
            assertThat(pending).hasSize(3);
            assertThat(decodingThreads).containsExactly(Thread.currentThread(),Thread.currentThread());
            verify(benefitRepository).findAllByPartnerIdsWithPartner(List.of(3L));
        } finally { eventLoop.shutdownNow(); }
    }

    @Mock
    private BenefitRepository benefitRepository;

    @Mock
    private BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;

    @Mock
    private CarrierTierBenefitRepository carrierTierBenefitRepository;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @InjectMocks
    private PartnerBenefitCacheService cacheService;

    @Test
    void getBenefitsBatchDispatchesCacheReadsAsynchronouslyBeforeLoadingMisses() {
        List<BenefitCacheDto> cachedBenefits = new ArrayList<>();

        when(cacheManager.getCache("partner-benefits")).thenReturn(cache);
        doReturn(CompletableFuture.completedFuture(new SimpleValueWrapper(cachedBenefits)))
                .when(cache).retrieve(1L);
        doReturn(CompletableFuture.completedFuture(null)).when(cache).retrieve(2L);
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(2L))).thenReturn(List.of());

        var result = cacheService.getBenefitsBatch(List.of(1L, 2L));

        assertThat(result).containsEntry(1L, cachedBenefits).containsEntry(2L, List.of());
        verify(cache).retrieve(1L);
        verify(cache).retrieve(2L);
        verify(cache, never()).get(1L);
        verify(cache, never()).get(2L);
    }

    @Test
    void getBenefitsBatchFallsBackToSynchronousReadsWhenCacheDoesNotSupportAsyncRetrieval() {
        List<BenefitCacheDto> cachedBenefits = new ArrayList<>();

        when(cacheManager.getCache("partner-benefits")).thenReturn(cache);
        when(cache.retrieve(1L)).thenThrow(new UnsupportedOperationException("unsupported"));
        when(cache.get(1L)).thenReturn(new SimpleValueWrapper(cachedBenefits));

        var result = cacheService.getBenefitsBatch(List.of(1L));

        assertThat(result).containsEntry(1L, cachedBenefits);
        verify(cache).get(1L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void singleAndBatchLoadingExcludeInactiveBenefits(boolean batch) {
        Partner partner = Partner.builder().partnerId(3L).partnerName("종료 제휴처").build();
        Benefit inactiveBenefit = Benefit.builder()
                .benefitId(30L)
                .partner(partner)
                .benefitName("종료 혜택")
                .active(false)
                .build();

        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(3L)))
                .thenReturn(List.of(inactiveBenefit));

        var result = batch ? cacheService.getBenefitsBatch(List.of(3L)).get(3L) : cacheService.getBenefits(3L);

        assertThat(result).isEmpty();
        assertCacheRoundTrip(result);
        verify(benefitCarrierPolicyRepository, never()).findAllByBenefitIn(org.mockito.ArgumentMatchers.anyList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void singleAndBatchLoadingKeepOnlyActivePoliciesAndSerializableTiers(boolean batch) {
        Partner partner = Partner.builder().partnerId(3L).build();
        Benefit benefit = Benefit.builder().benefitId(30L).partner(partner)
                .benefitName("할인 혜택").active(true).build();
        BenefitCarrierPolicy activePolicy = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(40L).benefit(benefit).carrier(Carrier.KT)
                .usageType(UsageType.OFFLINE).active(true).build();
        BenefitCarrierPolicy inactivePolicy = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(41L).benefit(benefit).carrier(Carrier.SKT)
                .usageType(UsageType.ONLINE).active(false).build();
        CarrierTierBenefit tier = CarrierTierBenefit.builder()
                .benefitCarrierPolicy(activePolicy).grade(Grade.KT_VIP).context("10% 할인").build();
        List<Long> partnerIds = batch ? List.of(3L, 4L) : List.of(3L);
        when(benefitRepository.findAllByPartnerIdsWithPartner(partnerIds)).thenReturn(List.of(benefit));
        when(benefitCarrierPolicyRepository.findAllByBenefitIn(List.of(benefit)))
                .thenReturn(List.of(activePolicy, inactivePolicy));
        when(carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(List.of(activePolicy)))
                .thenReturn(List.of(tier));

        List<BenefitCacheDto> result;
        if (batch) {
            var byPartner = cacheService.getBenefitsBatch(List.of(3L, 4L, 3L));
            result = byPartner.get(3L);
            assertThat(byPartner.get(4L)).isEmpty();
            assertCacheRoundTrip(byPartner.get(4L));
        } else {
            result = cacheService.getBenefits(3L);
        }

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.getBenefitId()).isEqualTo(30L);
            assertThat(dto.getUsageType()).isEqualTo(UsageType.OFFLINE);
            assertThat(dto.getTierBenefits()).singleElement().satisfies(value -> {
                assertThat(value.getCarrier()).isEqualTo(Carrier.KT);
                assertThat(value.getGrade()).isEqualTo(Grade.KT_VIP);
                assertThat(value.getContext()).isEqualTo("10% 할인");
            });
        });
        assertCacheRoundTrip(result);
        verify(benefitRepository).findAllByPartnerIdsWithPartner(partnerIds);
        verify(benefitCarrierPolicyRepository).findAllByBenefitIn(List.of(benefit));
        verify(carrierTierBenefitRepository).findAllByBenefitCarrierPolicyIn(List.of(activePolicy));
    }

    private void assertCacheRoundTrip(List<BenefitCacheDto> benefits) {
        RedisCacheManager manager = (RedisCacheManager) new CacheConfig()
                .cacheManager(mock(RedisConnectionFactory.class));
        manager.afterPropertiesSet();
        var serializer = manager.getCacheConfigurations().get("partner-benefits").getValueSerializationPair();

        assertThat(serializer.read(serializer.write(benefits)))
                .usingRecursiveComparison().isEqualTo(benefits);
    }
}
