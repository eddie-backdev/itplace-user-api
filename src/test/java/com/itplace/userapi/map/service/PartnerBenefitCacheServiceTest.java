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
