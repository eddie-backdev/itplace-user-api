package com.itplace.userapi.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.metrics.cache.RedisCacheMetrics;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands;

class CacheConfigTest {

    @Test
    void typedBenefitSerializerReadsExistingValuesAndKeepsRollbackCompatibleBytes() {
        var manager = (RedisCacheManager) new CacheConfig().cacheManager(mock(RedisConnectionFactory.class), org.springframework.data.redis.cache.CacheStatisticsCollector.create());
        manager.afterPropertiesSet();
        var typed = manager.getCacheConfigurations().get("partner-benefits").getValueSerializationPair();
        // 다른 캐시에 유지된 generic serializer가 기존 혜택 캐시 형식이다.
        var legacy = manager.getCacheConfigurations().get("map-store-clusters").getValueSerializationPair();
        var tiers = new java.util.ArrayList<>(java.util.List.of(
                com.itplace.userapi.map.dto.response.TierBenefitDto.builder().benefitId(7L).context("현장 할인").build()));
        var populated = new java.util.ArrayList<>(java.util.List.of(new com.itplace.userapi.map.dto.BenefitCacheDto(
                7L,"혜택",com.itplace.userapi.benefit.entity.enums.UsageType.OFFLINE,null,tiers)));
        for (var value : java.util.List.of(populated,new java.util.ArrayList<>())) {
            assertThat(typed.read(legacy.write(value))).usingRecursiveComparison().isEqualTo(value);
            assertThat(legacy.read(typed.write(value))).usingRecursiveComparison().isEqualTo(value);
            assertThat(typed.write(value)).isEqualTo(legacy.write(value));
        }
    }

    @Test
    void cachesAreRegisteredWithStatisticsAndPurposeSpecificTtl() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisStringCommands stringCommands = mock(RedisStringCommands.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(stringCommands.get(any(byte[].class))).thenReturn(null);

        CacheManager cacheManager = new CacheConfig().cacheManager(connectionFactory, org.springframework.data.redis.cache.CacheStatisticsCollector.create());
        ((RedisCacheManager) cacheManager).afterPropertiesSet();

        assertThat(cacheManager.getCacheNames()).contains("partner-benefits", "map-store-clusters");
        RedisCacheManager redisCacheManager = (RedisCacheManager) cacheManager;
        for (int i = 0; i < 100; i++) {
            assertThat(redisCacheManager.getCacheConfigurations().get("partner-benefits").getTtlFunction()
                    .getTimeToLive(1L, java.util.List.of())).isBetween(Duration.ofMinutes(55), Duration.ofHours(1));
        }
        assertThat(redisCacheManager.getCacheConfigurations().get("map-store-clusters").getTtl())
                .isEqualTo(Duration.ofMinutes(1));
        RedisCache cache = (RedisCache) cacheManager.getCache("partner-benefits");
        assertThat(cache).isNotNull();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        meterRegistry.config().meterFilter(new CacheConfig().redisCachePendingCounterFilter());
        new RedisCacheMetrics(cache, Tags.of("cache.manager", "cache")).bindTo(meterRegistry);

        cache.get(1L);

        assertThat(cache.getStatistics().getGets()).isEqualTo(1);
        assertThat(cache.getStatistics().getMisses()).isEqualTo(1);
        assertThat(meterRegistry.get("cache.gets")
                .tags("cache", "partner-benefits", "result", "miss")
                .functionCounter()
                .count()).isEqualTo(1);
        assertThat(meterRegistry.find("cache.gets")
                .tags("cache", "partner-benefits", "result", "pending")
                .meter()).isNull();
    }
}
