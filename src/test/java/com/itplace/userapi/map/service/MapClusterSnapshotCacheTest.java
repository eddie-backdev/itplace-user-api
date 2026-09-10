package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.map.repository.StoreRepository;
import com.itplace.userapi.map.repository.projection.StoreClusterProjection;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class MapClusterSnapshotCacheTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.4").withExposedPorts(6379);
    static HikariDataSource source;
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static JdbcTemplate jdbc;
    static String originalSql;
    final ObjectMapper mapper = new ObjectMapper();
    MutableClock clock;
    MapClusterSnapshotProperties properties;
    SimpleMeterRegistry registry;
    MapClusterSnapshotCache cache;

    @BeforeAll
    static void connect() throws Exception {
        source = new HikariDataSource();
        source.setJdbcUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setMaximumPoolSize(4);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE map_region_store_summary (
                    aggregation_unit text, region_type text, region_hash text, region_key text,
                    region_name text, category text, store_count bigint
                );
                CREATE TABLE map_region_anchor (region_type text, region_key text, latitude float8, longitude float8);
                """);
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        originalSql = StoreRepository.class.getMethod("findStoreClustersInView", double.class, double.class,
                double.class, double.class, String.class, int.class, String.class).getAnnotation(Query.class).value();
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.destroy();
        if (source != null) source.close();
    }

    @BeforeEach
    void prepare() {
        redis.delete(MapClusterSnapshotCache.KEY);
        jdbc.execute("TRUNCATE map_region_store_summary, map_region_anchor");
        jdbc.execute("""
                INSERT INTO map_region_anchor VALUES
                    ('LEGAL_DONG','one',37.5,127.0), ('LEGAL_DONG','two',37.6,127.1),
                    ('TOWN','town',37.5,127.0), ('CITY','city',37.0,127.0),
                    ('LEGAL_DONG','negative',-0.1,-0.1), ('LEGAL_DONG','zero',0,0);
                INSERT INTO map_region_store_summary VALUES
                    ('LEGAL_DONG','LEGAL_DONG','a','one','가나다','카페',2),
                    ('LEGAL_DONG','LEGAL_DONG','a','one','라마바','푸드',3),
                    ('LEGAL_DONG','LEGAL_DONG','b','two','경계','카페',5),
                    ('TOWN','TOWN','c','town','시군구','카페',10),
                    ('TOWN','CITY','d','city','fallback','카페',10),
                    ('CITY','CITY','d','city','시도','카페',20),
                    ('LEGAL_DONG','LEGAL_DONG','e','negative',NULL,'',0),
                    ('LEGAL_DONG','LEGAL_DONG','f','zero','원점','푸드',1);
                """);
        clock = new MutableClock();
        properties = new MapClusterSnapshotProperties();
        registry = new SimpleMeterRegistry();
        cache = new MapClusterSnapshotCache(redis, mapper, source, properties, registry, clock);
    }

    @Test
    void snapshotMatchesOriginalSqlAcrossLevelsCategoriesBoundsAndRandomMoves() {
        cache.synchronizeSnapshot();
        Random random = new Random(42);
        int checked = 0;
        for (String unit : List.of("LEGAL_DONG", "TOWN", "CITY")) {
            for (int level : List.of(1, 4, 5, 6, 9, 10, 14)) {
                for (String category : new String[]{null, "카페", "푸드", "", "missing"}) {
                    for (double[] bounds : List.of(new double[]{-90,90,-180,180},
                            new double[]{37.5,37.6,127.0,127.1}, new double[]{-0.1,0,-0.1,0},
                            new double[]{37.5,37.5,127,127},
                            new double[]{37.4 + random.nextDouble()/5,37.65,126.9 + random.nextDouble()/5,127.15})) {
                        MapSqlParameterSource params = new MapSqlParameterSource().addValue("administrativeUnitType", unit)
                                .addValue("category", category, java.sql.Types.VARCHAR).addValue("mapLevel", level)
                                .addValue("minLat", bounds[0]).addValue("maxLat", bounds[1])
                                .addValue("minLng", bounds[2]).addValue("maxLng", bounds[3]);
                        List<Map<String, Object>> expected = new NamedParameterJdbcTemplate(source).queryForList(originalSql, params);
                        List<Map<String, Object>> actual = cache.find(unit, bounds[0], bounds[1], bounds[2], bounds[3], category, level)
                                .orElseThrow().stream().map(MapClusterSnapshotCacheTest::fields).toList();
                        assertThat(actual).as("unit=%s level=%s category=%s", unit, level, category).isEqualTo(expected);
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(525);
        assertThat(registry.get("map.cluster.snapshot.builds").counter().count()).isEqualTo(1);
    }

    @Test
    void twoInstancesShareOneBuildAndSwitchCompleteVersionsOnPoll() {
        SimpleMeterRegistry secondMetrics = new SimpleMeterRegistry();
        MapClusterSnapshotCache second = new MapClusterSnapshotCache(redis, mapper, source, properties, secondMetrics, clock);
        cache.synchronizeSnapshot();
        second.synchronizeSnapshot();
        assertThat(secondMetrics.get("map.cluster.snapshot.builds").counter().count()).isZero();
        assertThat(secondMetrics.get("map.cluster.snapshot.installs").counter().count()).isEqualTo(1);
        assertThat(total(second)).isEqualTo(5);
        jdbc.update("UPDATE map_region_store_summary SET store_count=20 WHERE region_hash='a' AND category='카페'");
        clock.advance(30_001);
        cache.synchronizeSnapshot();
        assertThat(total(cache)).isEqualTo(23);
        assertThat(total(second)).isEqualTo(5);
        second.synchronizeSnapshot();
        assertThat(total(second)).isEqualTo(23);
        assertThat(secondMetrics.get("map.cluster.snapshot.builds").counter().count()).isZero();
        assertThat(redis.opsForHash().size(MapClusterSnapshotCache.KEY)).isEqualTo(3);
        Object version = redis.opsForHash().get(MapClusterSnapshotCache.KEY, "version");
        assertThat(redis.execute(MapClusterSnapshotCache.PUBLISH, List.of(MapClusterSnapshotCache.KEY),
                Long.toString(clock.millis() - 30_001), "late-publisher", "{}")).isZero();
        assertThat(redis.opsForHash().get(MapClusterSnapshotCache.KEY, "version")).isEqualTo(version);
    }

    @Test
    void redisFailureKeepsOnlyUsableSnapshotThenFallsBackWithoutRequestIo() {
        StringRedisTemplate failingRedis = spy(redis);
        MapClusterSnapshotCache instance = new MapClusterSnapshotCache(failingRedis, mapper, source, properties, registry, clock);
        instance.synchronizeSnapshot();
        doThrow(new RedisConnectionFailureException("offline")).when(failingRedis).opsForHash();
        instance.synchronizeSnapshot();
        assertThat(total(instance)).isEqualTo(5);
        clock.advance(60_000);
        instance.synchronizeSnapshot();
        assertThat(instance.find("LEGAL_DONG",37.5,37.5,127,127,null,5)).isEmpty();
    }

    @Test
    void malformedOversizedAndFutureSharedDataAreNotInstalled() {
        cache.synchronizeSnapshot();
        redis.opsForHash().putAll(MapClusterSnapshotCache.KEY, Map.of("version","bad", "data","{broken"));
        cache.synchronizeSnapshot();
        assertThat(total(cache)).isEqualTo(5);
        properties.setMaxPayloadBytes(1024);
        redis.opsForHash().put(MapClusterSnapshotCache.KEY, "data", "x".repeat(1025));
        cache.synchronizeSnapshot();
        assertThat(total(cache)).isEqualTo(5);
        assertThat(registry.get("map.cluster.snapshot.failures").counter().count()).isEqualTo(2);
        clock.advance(-6_000);
        assertThat(cache.find("LEGAL_DONG",37.5,37.5,127,127,null,5)).isEmpty();
    }

    @Test
    void limitsRejectWholeGenerationAndReleaseDatabaseLock() throws Exception {
        properties.setMaxRegions(1);
        cache.synchronizeSnapshot();
        assertThat(redis.hasKey(MapClusterSnapshotCache.KEY)).isFalse();
        assertThat(cache.find("LEGAL_DONG",-90,90,-180,180,null,5)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory'", Long.class)).isZero();
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_try_advisory_lock(" + MapRegionStoreSummaryRefreshService.REFRESH_LOCK_KEY + ")")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).isTrue();
            statement.executeQuery("SELECT pg_advisory_unlock(" + MapRegionStoreSummaryRefreshService.REFRESH_LOCK_KEY + ")").close();
        }
        properties.setMaxRegions(20_000);
        properties.setMaxCategoryCounts(1);
        cache.synchronizeSnapshot();
        assertThat(redis.hasKey(MapClusterSnapshotCache.KEY)).isFalse();
        properties.setMaxCategoryCounts(100_000);
        properties.setMaxPayloadBytes(1024);
        cache.synchronizeSnapshot();
        assertThat(redis.hasKey(MapClusterSnapshotCache.KEY)).isFalse();
    }

    @Test
    void summaryRefreshLockPreventsSnapshotPublishing() throws Exception {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT pg_advisory_lock(" + MapRegionStoreSummaryRefreshService.REFRESH_LOCK_KEY + ")").close();
            try {
                cache.synchronizeSnapshot();
                assertThat(redis.hasKey(MapClusterSnapshotCache.KEY)).isFalse();
            } finally {
                statement.executeQuery("SELECT pg_advisory_unlock(" + MapRegionStoreSummaryRefreshService.REFRESH_LOCK_KEY + ")").close();
            }
        }
        cache.synchronizeSnapshot();
        assertThat(total(cache)).isEqualTo(5);
    }

    @Test
    void readersSeeCompleteGenerationsWhileRefreshingAndEmptySnapshotsAreCacheHits() throws Exception {
        properties.setMaximumAgeMs(3_600_000);
        cache.synchronizeSnapshot();
        var executor = Executors.newFixedThreadPool(5);
        try {
            List<java.util.concurrent.Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                readers.add(executor.submit(() -> {
                    for (int j = 0; j < 10_000; j++) assertThat(total(cache)).isIn(5L, 10L);
                }));
            }
            for (int i = 0; i < 10; i++) {
                jdbc.update("UPDATE map_region_store_summary SET store_count=? WHERE region_hash='a' AND category='카페'", i%2==0 ? 7 : 2);
                clock.advance(30_001);
                cache.synchronizeSnapshot();
            }
            for (var reader : readers) reader.get(20, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        jdbc.execute("TRUNCATE map_region_store_summary");
        clock.advance(30_001);
        cache.synchronizeSnapshot();
        assertThat(cache.find("LEGAL_DONG",-90,90,-180,180,null,5)).hasValue(List.of());
        properties.setEnabled(false);
        cache.synchronizeSnapshot();
        assertThat(cache.find("LEGAL_DONG",-90,90,-180,180,null,5)).isEmpty();
        assertThat(registry.get("map.cluster.snapshot.regions").gauge().value()).isZero();
    }

    private static long total(MapClusterSnapshotCache instance) {
        return instance.find("LEGAL_DONG",37.5,37.5,127,127,null,5).orElseThrow().get(0).getCount();
    }

    private static Map<String,Object> fields(StoreClusterProjection row) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("clusterId",row.getClusterId()); result.put("category",row.getCategory());
        result.put("administrativeUnitType",row.getAdministrativeUnitType());
        result.put("administrativeUnitName",row.getAdministrativeUnitName());
        result.put("latitude",row.getLatitude()); result.put("longitude",row.getLongitude());
        result.put("count",row.getCount());
        return result;
    }

    static final class MutableClock extends Clock {
        final AtomicLong now = new AtomicLong(1_800_000_000_000L);
        void advance(long millis) { now.addAndGet(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }
}
