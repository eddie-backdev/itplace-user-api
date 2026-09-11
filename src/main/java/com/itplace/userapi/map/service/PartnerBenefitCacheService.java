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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.util.ByteUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class PartnerBenefitCacheService {

    private static final String CACHE_NAME = "partner-benefits";
    private static final int MAX_IN_FLIGHT = 1024;
    private static final byte[] CACHED_NULL = org.springframework.data.redis.serializer.RedisSerializer.java().serialize(NullValue.INSTANCE);
    private static final byte[] FILL_SCRIPT = bytes("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            for i = 2, #KEYS do
                redis.call('SET', KEYS[i], ARGV[i * 2 - 2], 'PX', ARGV[i * 2 - 1])
            end
            return 1
            """);
    private static final byte[] INVALIDATE_SCRIPT = bytes("""
            redis.call('SET', KEYS[1], ARGV[1])
            local deleted = 0
            for i = 2, #KEYS do deleted = deleted + redis.call('DEL', KEYS[i]) end
            return deleted
            """);

    private final BenefitRepository benefitRepository;
    private final BenefitCarrierPolicyRepository benefitCarrierPolicyRepository;
    private final CarrierTierBenefitRepository carrierTierBenefitRepository;
    private final CacheManager cacheManager;
    private final RedisConnectionFactory redisConnectionFactory;
    private final CacheStatisticsCollector statistics;
    private final PlatformTransactionManager transactionManager;
    // 완료 후 즉시 제거한다. 혜택 데이터 L1이 아니라 진행 중인 load만 최대 1,024개 공유한다.
    private final Map<LoadKey, CompletableFuture<List<BenefitCacheDto>>> inFlight = new HashMap<>();

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<Long, List<BenefitCacheDto>> getBenefitsBatch(List<Long> partnerIds) {
        List<Long> uniqueIds = partnerIds.stream().distinct().toList();
        if (uniqueIds.isEmpty()) return Map.of();
        Cache cache = cacheManager.getCache(CACHE_NAME);
        CacheRead read = readCachedBenefits(cache, uniqueIds);
        Map<Long, List<BenefitCacheDto>> result = new HashMap<>(read.values());
        Map<Long, CompletableFuture<List<BenefitCacheDto>>> owned = new LinkedHashMap<>();
        Map<Long, CompletableFuture<List<BenefitCacheDto>>> waiting = new LinkedHashMap<>();
        synchronized (inFlight) {
            for (Long id : uniqueIds) {
                if (result.containsKey(id)) continue;
                LoadKey key = new LoadKey(read.generation(), id);
                CompletableFuture<List<BenefitCacheDto>> existing = inFlight.get(key);
                if (existing != null) {
                    waiting.put(id, existing);
                } else {
                    CompletableFuture<List<BenefitCacheDto>> future = new CompletableFuture<>();
                    owned.put(id, future);
                    // ponytail: registry 상한 초과는 직접 load. 분산 stampede 억제는 실제 cold 부하에서 필요할 때 추가한다.
                    if (inFlight.size() < MAX_IN_FLIGHT) inFlight.put(key, future);
                }
            }
        }
        // 소유한 batch를 먼저 완료한 뒤 다른 batch를 기다려 [A,B]/[B,A] 교착을 피한다.
        if (!owned.isEmpty()) {
            try {
                Map<Long, List<BenefitCacheDto>> loaded = loadFromSource(new ArrayList<>(owned.keySet()));
                writeCachedBenefits(cache, read.generation(), loaded);
                owned.forEach((id, future) -> future.complete(loaded.get(id)));
                result.putAll(loaded);
            } catch (RuntimeException failure) {
                owned.values().forEach(future -> future.completeExceptionally(failure));
                throw failure;
            } finally {
                synchronized (inFlight) {
                    owned.forEach((id, future) -> inFlight.remove(new LoadKey(read.generation(), id), future));
                }
            }
        }
        waiting.forEach((id, future) -> result.put(id, await(future)));
        return result;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<BenefitCacheDto> getBenefits(Long partnerId) {
        return getBenefitsBatch(List.of(partnerId)).get(partnerId);
    }

    private CacheRead readCachedBenefits(Cache cache, List<Long> partnerIds) {
        Map<Long, List<BenefitCacheDto>> values = new HashMap<>();
        if (cache == null) return new CacheRead("uncached", values);
        if (!(cache instanceof RedisCache redisCache)
                || redisCache.getCacheConfiguration().isTimeToIdleEnabled()) {
            // TTI 캐시는 GETEX 동작을 유지한다. 운영 partner-benefits는 고정 TTL MGET 경로다.
            partnerIds.forEach(id -> {
                Cache.ValueWrapper wrapper = cache.get(id);
                if (wrapper != null) values.put(id, cachedList(wrapper.get()));
            });
            return new CacheRead("local", values);
        }
        byte[][] keys = cacheKeys(redisCache, partnerIds);
        List<byte[]> payloads;
        try (var connection = redisConnectionFactory.getConnection()) {
            payloads = connection.stringCommands().mGet(keys);
            if (payloads.get(0) == null) {
                // UUID는 Redis 재시작/marker eviction 이후에도 이전 load 세대와 충돌하지 않는다.
                connection.stringCommands().setNX(keys[0], bytes(UUID.randomUUID().toString()));
                payloads = connection.stringCommands().mGet(keys);
            }
        }
        if (payloads.get(0) == null) throw new IllegalStateException("혜택 캐시 세대를 읽을 수 없습니다.");
        for (int i = 0; i < partnerIds.size(); i++) {
            statistics.incGets(CACHE_NAME);
            byte[] payload = payloads.get(i + 1);
            if (payload == null) {
                statistics.incMisses(CACHE_NAME);
            } else {
                statistics.incHits(CACHE_NAME);
                // JSON 해석은 Lettuce 이벤트 루프가 아니라 요청 스레드에서 한다.
                Object decoded = redisCache.isAllowNullValues() && java.util.Arrays.equals(payload, CACHED_NULL)
                        ? null : redisCache.getCacheConfiguration().getValueSerializationPair().read(ByteBuffer.wrap(payload));
                values.put(partnerIds.get(i), cachedList(decoded));
            }
        }
        return new CacheRead(new String(payloads.get(0), StandardCharsets.UTF_8), values);
    }

    private Map<Long, List<BenefitCacheDto>> loadFromSource(List<Long> partnerIds) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setTimeout(5);
        // readOnly=false가 source를 선택한다. replica lag로 갱신 전 혜택이 한 시간 다시 캐싱되는 것을 방지한다.
        return transaction.execute(status -> loadBenefits(partnerIds));
    }

    private void writeCachedBenefits(Cache cache, String generation, Map<Long, List<BenefitCacheDto>> loaded) {
        if (cache == null) return;
        if (!(cache instanceof RedisCache redisCache)
                || redisCache.getCacheConfiguration().isTimeToIdleEnabled()) {
            loaded.forEach(cache::put);
            return;
        }
        List<Long> ids = new ArrayList<>(loaded.keySet());
        byte[][] keys = cacheKeys(redisCache, ids);
        List<byte[]> arguments = new ArrayList<>(List.of(keys));
        arguments.add(bytes(generation));
        var configuration = redisCache.getCacheConfiguration();
        for (Long id : ids) {
            arguments.add(ByteUtils.getBytes(configuration.getValueSerializationPair().write(loaded.get(id))));
            arguments.add(bytes(Long.toString(configuration.getTtlFunction().getTimeToLive(id, loaded.get(id)).toMillis())));
        }
        try (var connection = redisConnectionFactory.getConnection()) {
            Long written = connection.scriptingCommands().eval(FILL_SCRIPT, ReturnType.INTEGER,
                    keys.length, arguments.toArray(byte[][]::new));
            if (Long.valueOf(1).equals(written)) ids.forEach(id -> statistics.incPuts(CACHE_NAME));
        }
    }

    /** DB commit 이후에만 무효화한다. Redis 실패는 숨기지 않으며 동일 import 재시도로 재무효화할 수 있다. */
    public void invalidateAfterCommit(List<Long> partnerIds) {
        List<Long> uniqueIds = partnerIds.stream().distinct().toList();
        if (uniqueIds.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate(uniqueIds); }
            });
        } else {
            invalidate(uniqueIds);
        }
    }

    private void invalidate(List<Long> partnerIds) {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) return;
        if (!(cache instanceof RedisCache redisCache)) {
            partnerIds.forEach(cache::evict);
            return;
        }
        byte[][] keys = cacheKeys(redisCache, partnerIds);
        List<byte[]> arguments = new ArrayList<>(List.of(keys));
        arguments.add(bytes(UUID.randomUUID().toString()));
        try (var connection = redisConnectionFactory.getConnection()) {
            Long deleted = connection.scriptingCommands().eval(INVALIDATE_SCRIPT, ReturnType.INTEGER,
                    keys.length, arguments.toArray(byte[][]::new));
            if (deleted != null) statistics.incDeletesBy(CACHE_NAME, deleted.intValue());
        }
    }

    private byte[][] cacheKeys(RedisCache cache, List<Long> ids) {
        byte[][] keys = new byte[ids.size() + 1][];
        keys[0] = cacheKey(cache, "__generation");
        for (int i = 0; i < ids.size(); i++) keys[i + 1] = cacheKey(cache, ids.get(i));
        return keys;
    }

    private byte[] cacheKey(RedisCache cache, Object id) {
        var configuration = cache.getCacheConfiguration();
        String key = configuration.getConversionService().convert(id, String.class);
        if (configuration.usePrefix()) key = configuration.getKeyPrefixFor(cache.getName()) + key;
        return ByteUtils.getBytes(configuration.getKeySerializationPair().write(key));
    }

    @SuppressWarnings("unchecked")
    private static List<BenefitCacheDto> cachedList(Object value) {
        return value == null || value == NullValue.INSTANCE ? new ArrayList<>() : (List<BenefitCacheDto>) value;
    }

    private static List<BenefitCacheDto> await(CompletableFuture<List<BenefitCacheDto>> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CompletionException(interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new CompletionException(failure);
        }
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private record CacheRead(String generation, Map<Long, List<BenefitCacheDto>> values) {}
    private record LoadKey(String generation, Long partnerId) {}

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
