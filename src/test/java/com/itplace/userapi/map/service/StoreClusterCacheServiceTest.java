package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.dto.response.MapStoreClusterResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

@ExtendWith(MockitoExtension.class)
class StoreClusterCacheServiceTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    private StoreClusterCacheService cacheService;

    @BeforeEach
    void setUp() {
        when(cacheManager.getCache("map-store-clusters")).thenReturn(cache);
        cacheService = new StoreClusterCacheService(cacheManager);
    }

    @Test
    void getOrLoad_returnsCachedValueWithoutCallingLoader() {
        List<MapStoreClusterResponse> cached = List.of(mock(MapStoreClusterResponse.class));
        when(cache.get("viewport")).thenReturn(() -> cached);
        AtomicInteger loadCount = new AtomicInteger();

        List<MapStoreClusterResponse> result = cacheService.getOrLoad("viewport", () -> {
            loadCount.incrementAndGet();
            return List.of();
        });

        assertThat(result).isSameAs(cached);
        assertThat(loadCount).hasValue(0);
    }

    @Test
    void getOrLoad_mergesConcurrentMissesForTheSameKey() throws Exception {
        int concurrentRequests = 20;
        when(cache.get("viewport")).thenReturn(null);
        List<MapStoreClusterResponse> loaded = List.of(mock(MapStoreClusterResponse.class));
        AtomicInteger loadCount = new AtomicInteger();
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        CyclicBarrier startTogether = new CyclicBarrier(concurrentRequests);
        Supplier<List<MapStoreClusterResponse>> loader = () -> {
            loadCount.incrementAndGet();
            loaderStarted.countDown();
            try {
                if (!releaseLoader.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("loader release timeout");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return loaded;
        };

        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        try {
            List<Future<List<MapStoreClusterResponse>>> futures = new ArrayList<>();
            for (int index = 0; index < concurrentRequests; index++) {
                futures.add(executor.submit(() -> {
                    startTogether.await(5, TimeUnit.SECONDS);
                    return cacheService.getOrLoad("viewport", loader);
                }));
            }

            assertThat(loaderStarted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            releaseLoader.countDown();

            for (Future<List<MapStoreClusterResponse>> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(loaded);
            }
        } finally {
            releaseLoader.countDown();
            executor.shutdownNow();
        }

        assertThat(loadCount).hasValue(1);
        verify(cache, times(1)).put(eq("viewport"), eq(loaded));
    }
}
