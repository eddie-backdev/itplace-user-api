package com.itplace.userapi.common.redis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.itplace.userapi.map.dto.BenefitCacheDto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final String PARTNER_BENEFITS_CACHE = "partner-benefits";
    private static final String MAP_STORE_CLUSTERS_CACHE = "map-store-clusters";

    /**
     * Redis의 로컬 통계는 get, hit, miss counter를 각각 읽어 snapshot을 만든다.
     * 높은 동시성에서는 비원자적 snapshot 때문에 pending(get - hit - miss)이 순간적으로
     * 음수가 될 수 있고, Prometheus counter 직렬화가 전체 scrape를 거부한다.
     *
     * <p>운영에 필요한 hit, miss, put 지표는 유지하고 파생 pending counter만 제외한다.</p>
     */
    @Bean
    public MeterFilter redisCachePendingCounterFilter() {
        return MeterFilter.deny(id -> "cache.gets".equals(id.getName())
                && "pending".equals(id.getTag("result")));
    }

    @Bean
    public CacheStatisticsCollector cacheStatisticsCollector() {
        return CacheStatisticsCollector.create();
    }

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory,
                                     CacheStatisticsCollector statistics) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // activateDefaultTypingAsProperty(@class 프로퍼티 기반) 대신 배열 기반 타입 직렬화 사용.
        // 이유: GenericJackson2JsonRedisSerializer는 역직렬화 시 ["타입명", {데이터}] 형식을 기대하는데,
        // AsProperty 방식은 List를 [{@class:..., ...}] 형태로 직렬화하여 역직렬화 시 MismatchedInputException 발생.
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL
        );

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer(mapper);
        // 혜택 목록은 타입을 알고 있으므로 타입 추론용 JSON 트리를 먼저 만들 필요가 없다.
        var benefitSerializer = new Jackson2JsonRedisSerializer<List<BenefitCacheDto>>(mapper,
                mapper.getTypeFactory().constructCollectionType(List.class, BenefitCacheDto.class));

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(serializer));

        return RedisCacheManager.builder(RedisCacheWriter.nonLockingRedisCacheWriter(redisConnectionFactory)
                        .withStatisticsCollector(statistics))
                .cacheDefaults(config.entryTtl(Duration.ofHours(1)))
                .initialCacheNames(Set.of(PARTNER_BENEFITS_CACHE, MAP_STORE_CLUSTERS_CACHE))
                .withInitialCacheConfigurations(Map.of(
                        // 기존 1시간 상한을 늘리지 않고 동시 적재된 제휴사의 만료를 분산한다.
                        PARTNER_BENEFITS_CACHE, config.entryTtl((key, value) -> Duration.ofSeconds(
                                3300 + java.util.concurrent.ThreadLocalRandom.current().nextInt(301)))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(benefitSerializer)),
                        MAP_STORE_CLUSTERS_CACHE, config.entryTtl(Duration.ofMinutes(1))
                ))
                .build();
    }
}
