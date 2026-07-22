package com.itplace.userapi.map.service;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.repository.BenefitCarrierPolicyRepository;
import com.itplace.userapi.benefit.repository.BenefitRepository;
import com.itplace.userapi.benefit.repository.CarrierTierBenefitRepository;
import com.itplace.userapi.benefit.support.BenefitContextSplitter;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import com.itplace.userapi.map.dto.response.TierBenefitDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PartnerBenefitCacheService {

    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final CacheManager cacheManager;

    /**
     * 여러 파트너의 혜택을 한 번에 로드한다.
     * 캐시 히트인 파트너는 Redis에서, 미스인 파트너는 3개 쿼리(benefits + policies + tierBenefits)로
     * 배치 로드 후 캐시에 적재한다.
     */
    @Transactional(readOnly = true)
    public Map<Long, List<BenefitCacheDto>> getBenefitsBatch(List<Long> partnerIds) {
        Map<Long, List<BenefitCacheDto>> result = new HashMap<>();
        List<Long> uncachedIds = new ArrayList<>();
        List<Long> uniquePartnerIds = partnerIds.stream().distinct().toList();

        Cache cache = cacheManager.getCache("partner-benefits");
        Map<Long, Cache.ValueWrapper> cachedByPartner = readCachedBenefits(cache, uniquePartnerIds);
        for (Long partnerId : uniquePartnerIds) {
            Cache.ValueWrapper cached = cachedByPartner.get(partnerId);
            if (cached != null) {
                @SuppressWarnings("unchecked")
                List<BenefitCacheDto> hit = (List<BenefitCacheDto>) cached.get();
                result.put(partnerId, hit != null ? hit : new ArrayList<>());
            } else {
                uncachedIds.add(partnerId);
            }
        }

        if (uncachedIds.isEmpty()) {
            return result;
        }

        List<Benefit> benefits = benefitRepository.findAllByPartnerIdsWithPartner(uncachedIds).stream()
                .filter(benefit -> Boolean.TRUE.equals(benefit.getActive()))
                .toList();
        List<BenefitCarrierPolicy> policies = benefits.isEmpty()
                ? List.of() : benefitCarrierPolicyRepository.findAllByBenefitIn(benefits).stream()
                .filter(policy -> Boolean.TRUE.equals(policy.getActive()))
                .toList();
        List<CarrierTierBenefit> carrierTierBenefits = policies.isEmpty()
                ? List.of() : carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies);

        Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit = policies.stream()
                .collect(Collectors.groupingBy(policy -> policy.getBenefit().getBenefitId()));
        Map<Long, List<CarrierTierBenefit>> carrierTierMap = carrierTierBenefits.stream()
                .collect(Collectors.groupingBy(tier -> tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));
        Map<Long, List<Benefit>> benefitsByPartner = benefits.stream()
                .collect(Collectors.groupingBy(b -> b.getPartner().getPartnerId()));

        for (Long partnerId : uncachedIds) {
            List<Benefit> partnerBenefits = benefitsByPartner.getOrDefault(partnerId, List.of());
            List<BenefitCacheDto> dtos = partnerBenefits.stream()
                    .map(b -> toBenefitCacheDto(
                            b,
                            policiesByBenefit.getOrDefault(b.getBenefitId(), List.of()),
                            carrierTierMap
                    ))
                    .collect(Collectors.toList());

            if (cache != null) {
                cache.put(partnerId, dtos);
            }
            result.put(partnerId, dtos);
        }

        return result;
    }

    private Map<Long, Cache.ValueWrapper> readCachedBenefits(Cache cache, List<Long> partnerIds) {
        if (cache == null || partnerIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, CompletableFuture<?>> futureByPartner = new HashMap<>();
        try {
            for (Long partnerId : partnerIds) {
                CompletableFuture<?> future = cache.retrieve(partnerId);
                futureByPartner.put(
                        partnerId,
                        future != null ? future : CompletableFuture.completedFuture(null)
                );
            }
        } catch (UnsupportedOperationException unsupported) {
            Map<Long, Cache.ValueWrapper> cachedByPartner = new HashMap<>();
            partnerIds.forEach(partnerId -> cachedByPartner.put(partnerId, cache.get(partnerId)));
            return cachedByPartner;
        }

        CompletableFuture.allOf(futureByPartner.values().toArray(CompletableFuture[]::new)).join();
        Map<Long, Cache.ValueWrapper> cachedByPartner = new HashMap<>();
        futureByPartner.forEach((partnerId, future) -> {
            Object cached = future.join();
            if (cached instanceof Cache.ValueWrapper wrapper) {
                cachedByPartner.put(partnerId, wrapper);
            } else if (cached != null) {
                cachedByPartner.put(partnerId, () -> cached);
            }
        });
        return cachedByPartner;
    }

    @Cacheable(value = "partner-benefits", key = "#partnerId")
    @Transactional(readOnly = true)
    public List<BenefitCacheDto> getBenefits(Long partnerId) {
        List<Benefit> benefits = benefitRepository.findAllByPartner_PartnerId(partnerId).stream()
                .filter(benefit -> Boolean.TRUE.equals(benefit.getActive()))
                .toList();
        if (benefits.isEmpty()) {
            // List.of() 대신 new ArrayList<>() 사용.
            // 이유: List.of()는 final 클래스(ImmutableCollections$ListN)를 반환하므로,
            // Jackson NON_FINAL 타입 설정에서 타입 래퍼가 생략되어 역직렬화 실패.
            return new ArrayList<>();
        }

        List<BenefitCarrierPolicy> policies = benefitCarrierPolicyRepository.findAllByBenefitIn(benefits).stream()
                .filter(policy -> Boolean.TRUE.equals(policy.getActive()))
                .toList();
        List<CarrierTierBenefit> carrierTierBenefits = policies.isEmpty()
                ? List.of() : carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies);
        Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit = policies.stream()
                .collect(Collectors.groupingBy(policy -> policy.getBenefit().getBenefitId()));
        Map<Long, List<CarrierTierBenefit>> carrierTierMap = carrierTierBenefits.stream()
                .collect(Collectors.groupingBy(tier -> tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));

        // .toList() 대신 .collect(Collectors.toList()) 사용 (외부/내부 리스트 모두 동일).
        // 이유: Java 16+ Stream.toList()는 final 클래스를 반환하여 Jackson이 타입 래퍼를 붙이지 않음.
        // 결과적으로 [[element1], [element2]] 구조로 직렬화되고,
        // 역직렬화 시 타입 id 자리에 START_ARRAY 토큰이 오면서 MismatchedInputException 발생.
        // Collectors.toList()는 ArrayList(non-final)를 반환하므로 타입 래퍼가 정상적으로 생성됨.
        return benefits.stream()
                .map(b -> toBenefitCacheDto(
                        b,
                        policiesByBenefit.getOrDefault(b.getBenefitId(), List.of()),
                        carrierTierMap
                ))
                .collect(Collectors.toList());
    }

    private BenefitCacheDto toBenefitCacheDto(
            Benefit benefit,
            List<BenefitCarrierPolicy> policies,
            Map<Long, List<CarrierTierBenefit>> carrierTierMap
    ) {
        List<TierBenefitDto> normalizedTiers = policies.stream()
                .flatMap(policy -> carrierTierMap
                        .getOrDefault(policy.getBenefitCarrierPolicyId(), List.of()).stream()
                        .map(tier -> toTierBenefitDto(benefit, policy, tier)))
                .collect(Collectors.toList());
        return new BenefitCacheDto(
                benefit.getBenefitId(),
                benefit.getBenefitName(),
                representativeUsageType(policies),
                benefit.getMainCategory(),
                normalizedTiers
        );
    }

    private TierBenefitDto toTierBenefitDto(Benefit benefit, BenefitCarrierPolicy policy, CarrierTierBenefit tier) {
        BenefitContextSplitter.SplitContext splitContext = BenefitContextSplitter.split(tier.getContext());
        return TierBenefitDto.builder()
                .benefitId(benefit.getBenefitId())
                .carrier(policy.getCarrier())
                .grade(tier.getGrade())
                .context(tier.getContext())
                .onlineContext(splitContext.onlineContext())
                .offlineContext(splitContext.offlineContext())
                .build();
    }

    private com.itplace.userapi.benefit.entity.enums.UsageType representativeUsageType(
            List<BenefitCarrierPolicy> policies
    ) {
        return policies.stream()
                .map(BenefitCarrierPolicy::getUsageType)
                .filter(type -> type == com.itplace.userapi.benefit.entity.enums.UsageType.OFFLINE
                        || type == com.itplace.userapi.benefit.entity.enums.UsageType.BOTH)
                .findFirst()
                .orElse(policies.isEmpty() ? null : policies.get(0).getUsageType());
    }
}
