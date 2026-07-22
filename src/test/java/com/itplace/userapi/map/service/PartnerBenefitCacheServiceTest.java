package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.partner.entity.Partner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleValueWrapper;

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

    @Test
    void getBenefitsBatch_doesNotCacheInactiveBenefits() {
        Partner partner = Partner.builder().partnerId(3L).partnerName("종료 제휴처").build();
        Benefit inactiveBenefit = Benefit.builder()
                .benefitId(30L)
                .partner(partner)
                .benefitName("종료 혜택")
                .active(false)
                .build();

        when(cacheManager.getCache("partner-benefits")).thenReturn(null);
        when(benefitRepository.findAllByPartnerIdsWithPartner(List.of(3L)))
                .thenReturn(List.of(inactiveBenefit));

        var result = cacheService.getBenefitsBatch(List.of(3L));

        assertThat(result).containsEntry(3L, List.of());
        verify(benefitCarrierPolicyRepository, never()).findAllByBenefitIn(org.mockito.ArgumentMatchers.anyList());
    }
}
