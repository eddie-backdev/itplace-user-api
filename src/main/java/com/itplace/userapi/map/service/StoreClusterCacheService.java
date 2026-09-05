package com.itplace.userapi.map.service;

import com.itplace.userapi.map.dto.response.MapStoreClusterResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoreClusterCacheService {

    private static final String CACHE_NAME = "map-store-clusters";

    private final CacheManager cacheManager;
    private final ConcurrentMap<String, CompletableFuture<List<MapStoreClusterResponse>>> inFlightLoads
            = new ConcurrentHashMap<>();

    /**
     * 같은 viewport cache key의 동시 miss를 한 번의 DB 조회로 합친다.
     *
     * <p>RedisCache의 non-locking writer는 {@code @Cacheable(sync = true)}의 value loader를
     * key 단위로 직렬화하지 않는다. 애플리케이션 단일 호스트에서 key별 single-flight를 적용해
     * 서로 다른 지도 영역은 병렬 조회하되 같은 영역의 중복 조회만 제거한다.</p>
     */
    public List<MapStoreClusterResponse> getOrLoad(
            String cacheKey,
            Supplier<List<MapStoreClusterResponse>> loader
    ) {
        Cache cache = getCache();
        List<MapStoreClusterResponse> cached = getCached(cache, cacheKey);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<List<MapStoreClusterResponse>> candidate = new CompletableFuture<>();
        CompletableFuture<List<MapStoreClusterResponse>> existing = inFlightLoads.putIfAbsent(cacheKey, candidate);
        if (existing != null) {
            return await(existing);
        }

        try {
            List<MapStoreClusterResponse> rechecked = getCached(cache, cacheKey);
            if (rechecked != null) {
                candidate.complete(rechecked);
                return rechecked;
            }

            List<MapStoreClusterResponse> loaded = loader.get();
            cache.put(cacheKey, loaded);
            candidate.complete(loaded);
            return loaded;
        } catch (RuntimeException | Error error) {
            candidate.completeExceptionally(error);
            throw error;
        } finally {
            inFlightLoads.remove(cacheKey, candidate);
        }
    }

    private Cache getCache() {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            throw new IllegalStateException("필수 캐시를 찾을 수 없습니다: " + CACHE_NAME);
        }
        return cache;
    }

    @SuppressWarnings("unchecked")
    private List<MapStoreClusterResponse> getCached(Cache cache, String cacheKey) {
        Cache.ValueWrapper cached = cache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        return (List<MapStoreClusterResponse>) cached.get();
    }

    private List<MapStoreClusterResponse> await(
            CompletableFuture<List<MapStoreClusterResponse>> existing
    ) {
        try {
            return existing.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }
}
