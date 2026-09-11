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
import java.nio.ByteBuffer;
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
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.util.ByteUtils;
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

        Map<Long, List<BenefitCacheDto>> loaded = loadBenefits(uncachedIds);
        if (cache != null) {
            loaded.forEach(cache::put);
        }
        result.putAll(loaded);
        return result;
    }

    private Map<Long, Cache.ValueWrapper> readCachedBenefits(Cache cache, List<Long> partnerIds) {
        if (cache == null || partnerIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, CompletableFuture<?>> futureByPartner = new HashMap<>();
        // RedisCache.retrieve는 완료 콜백에서 JSON을 해석해 Lettuce 이벤트 루프를 점유한다.
        // raw byte만 병렬로 받고 JSON 해석은 아래의 요청 스레드에서 수행한다.
        RedisCache redisCache = cache instanceof RedisCache candidate
                && !candidate.getCacheConfiguration().isTimeToIdleEnabled() ? candidate : null;
        try {
            for (Long partnerId : partnerIds) {
                CompletableFuture<?> future;
                if (redisCache == null) {
                    future = cache.retrieve(partnerId);
                } else {
                    var configuration = redisCache.getCacheConfiguration();
                    String key = configuration.getConversionService().convert(partnerId, String.class);
                    if (configuration.usePrefix()) key = configuration.getKeyPrefixFor(cache.getName()) + key;
                    future = redisCache.getNativeCache().retrieve(cache.getName(),
                            ByteUtils.getBytes(configuration.getKeySerializationPair().write(key)));
                }
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
            if (redisCache != null && cached instanceof byte[] bytes) {
                Object decoded = redisCache.getCacheConfiguration().getValueSerializationPair().read(ByteBuffer.wrap(bytes));
                cachedByPartner.put(partnerId, () -> decoded == NullValue.INSTANCE ? null : decoded);
                return;
            }
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
        return loadBenefits(List.of(partnerId)).get(partnerId);
    }

    // Redis NON_FINAL 타입 직렬화를 위해 빈 목록과 중첩 목록도 ArrayList로 만든다.
    private Map<Long, List<BenefitCacheDto>> loadBenefits(List<Long> partnerIds) {
        Map<Long, List<BenefitCacheDto>> result = new HashMap<>();
        List<Benefit> benefits = benefitRepository.findAllByPartnerIdsWithPartner(partnerIds).stream()
                .filter(benefit -> !Boolean.FALSE.equals(benefit.getActive()))
                .toList();
        List<BenefitCarrierPolicy> policies = benefits.isEmpty()
                ? List.of() : benefitCarrierPolicyRepository.findAllByBenefitIn(benefits).stream()
                .filter(policy -> !Boolean.FALSE.equals(policy.getActive()))
                .toList();
        List<CarrierTierBenefit> carrierTierBenefits = policies.isEmpty()
                ? List.of() : carrierTierBenefitRepository.findAllByBenefitCarrierPolicyIn(policies);

        Map<Long, List<BenefitCarrierPolicy>> policiesByBenefit = policies.stream()
                .collect(Collectors.groupingBy(policy -> policy.getBenefit().getBenefitId()));
        Map<Long, List<CarrierTierBenefit>> carrierTierMap = carrierTierBenefits.stream()
                .collect(Collectors.groupingBy(tier -> tier.getBenefitCarrierPolicy().getBenefitCarrierPolicyId()));
        Map<Long, List<Benefit>> benefitsByPartner = benefits.stream()
                .collect(Collectors.groupingBy(b -> b.getPartner().getPartnerId()));

        for (Long partnerId : partnerIds) {
            List<Benefit> partnerBenefits = benefitsByPartner.getOrDefault(partnerId, List.of());
            List<BenefitCacheDto> dtos = partnerBenefits.stream()
                    .map(b -> toBenefitCacheDto(
                            b,
                            policiesByBenefit.getOrDefault(b.getBenefitId(), List.of()),
                            carrierTierMap
                    ))
                    .collect(Collectors.toCollection(ArrayList::new));

            result.put(partnerId, dtos);
        }

        return result;
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
                .collect(Collectors.toCollection(ArrayList::new));
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
