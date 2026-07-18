package com.itplace.userapi.common.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void partnerBenefitsCacheIsRegisteredWithStatisticsEnabled() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);
        RedisStringCommands stringCommands = mock(RedisStringCommands.class);
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.stringCommands()).thenReturn(stringCommands);
        when(stringCommands.get(any(byte[].class))).thenReturn(null);

        CacheManager cacheManager = new CacheConfig().cacheManager(connectionFactory);
        ((RedisCacheManager) cacheManager).afterPropertiesSet();

        assertThat(cacheManager.getCacheNames()).contains("partner-benefits");
        RedisCache cache = (RedisCache) cacheManager.getCache("partner-benefits");
        assertThat(cache).isNotNull();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new RedisCacheMetrics(cache, Tags.of("cache.manager", "cache")).bindTo(meterRegistry);

        cache.get(1L);

        assertThat(cache.getStatistics().getGets()).isEqualTo(1);
        assertThat(cache.getStatistics().getMisses()).isEqualTo(1);
        assertThat(meterRegistry.get("cache.gets")
                .tags("cache", "partner-benefits", "result", "miss")
                .functionCounter()
                .count()).isEqualTo(1);
    }
}
