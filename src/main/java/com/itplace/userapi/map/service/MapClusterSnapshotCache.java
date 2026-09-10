package com.itplace.userapi.map.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.map.repository.projection.StoreClusterProjection;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MapClusterSnapshotCache {
    static final String KEY = "map-cluster-snapshot:v1";
    private static final TypeReference<Map<String, MapClusterSnapshot.CategoryCount>> COUNTS = new TypeReference<>() {};
    private static final DefaultRedisScript<String> READ = new DefaultRedisScript<>("""
            if redis.call('HSTRLEN', KEYS[1], 'data') > tonumber(ARGV[1]) then
                return redis.error_reply('map snapshot exceeds size limit')
            end
            return redis.call('HGET', KEYS[1], 'data')
            """, String.class);
    static final DefaultRedisScript<Long> PUBLISH = new DefaultRedisScript<>("""
            local previous = tonumber(redis.call('HGET', KEYS[1], 'createdAt'))
            if previous and previous >= tonumber(ARGV[1]) then return 0 end
            redis.call('HSET', KEYS[1], 'createdAt', ARGV[1], 'version', ARGV[2], 'data', ARGV[3])
            return 1
            """, Long.class);
    static final String SQL = """
            WITH categories AS (
                SELECT summary.aggregation_unit, summary.region_type, summary.region_hash, summary.region_key,
                       anchor.latitude, anchor.longitude, summary.category,
                       MIN(summary.region_name) AS name, SUM(summary.store_count)::bigint AS count
                FROM map_region_store_summary summary
                JOIN map_region_anchor anchor ON anchor.region_type = summary.region_type
                                             AND anchor.region_key = summary.region_key
                GROUP BY summary.aggregation_unit, summary.region_type, summary.region_hash, summary.region_key,
                         anchor.latitude, anchor.longitude, summary.category
            )
            SELECT aggregation_unit, region_type, region_hash, region_key, latitude, longitude,
                   MIN(name) AS name, SUM(count)::bigint AS total,
                   jsonb_object_agg(category, jsonb_build_object('name', name, 'count', count))::text AS counts
            FROM categories
            GROUP BY aggregation_unit, region_type, region_hash, region_key, latitude, longitude
            LIMIT ?
            """;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final DataSource source;
    private final MapClusterSnapshotProperties properties;
    private final Clock clock;
    private final Counter hits;
    private final Counter fallbacks;
    private final Counter builds;
    private final Counter installs;
    private final Counter failures;
    // 이전 세대 목록, 요청별 결과, Future/대기열을 유지하지 않는다.
    private volatile MapClusterSnapshot current;

    @Autowired
    public MapClusterSnapshotCache(StringRedisTemplate redis, ObjectMapper mapper,
                                   @Qualifier("sourceDataSource") DataSource source,
                                   MapClusterSnapshotProperties properties, MeterRegistry registry) {
        this(redis, mapper, source, properties, registry, Clock.systemUTC());
    }

    MapClusterSnapshotCache(StringRedisTemplate redis, ObjectMapper mapper, DataSource source,
                            MapClusterSnapshotProperties properties, MeterRegistry registry, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.source = source;
        this.properties = properties;
        this.clock = clock;
        hits = registry.counter("map.cluster.snapshot.requests", "result", "hit");
        fallbacks = registry.counter("map.cluster.snapshot.requests", "result", "fallback");
        builds = registry.counter("map.cluster.snapshot.builds");
        installs = registry.counter("map.cluster.snapshot.installs");
        failures = registry.counter("map.cluster.snapshot.failures");
        Gauge.builder("map.cluster.snapshot.regions", this, cache -> cache.current == null ? 0 : cache.current.data.regions().size()).register(registry);
        Gauge.builder("map.cluster.snapshot.json.bytes", this, cache -> cache.current == null ? 0 : cache.current.jsonBytes).register(registry);
        Gauge.builder("map.cluster.snapshot.age.seconds", this, cache -> cache.current == null ? -1 : Math.max(0, cache.clock.millis() - cache.current.data.createdAt()) / 1000.0).register(registry);
    }

    public boolean isEnabled() { return properties.isEnabled(); }

    public Optional<List<StoreClusterProjection>> find(String unit, double minLat, double maxLat,
                                                      double minLng, double maxLng, String category, int mapLevel) {
        MapClusterSnapshot snapshot = current;
        if (!isEnabled() || snapshot == null || !fresh(snapshot.data.createdAt(), properties.getMaximumAgeMs())
                || !Double.isFinite(minLat) || !Double.isFinite(maxLat)
                || !Double.isFinite(minLng) || !Double.isFinite(maxLng)) {
            fallbacks.increment();
            return Optional.empty();
        }
        hits.increment();
        return Optional.of(snapshot.select(unit, minLat, maxLat, minLng, maxLng, category, mapLevel));
    }

    @Scheduled(initialDelayString = "${app.map.cluster-snapshot.initial-delay-ms:1000}",
            fixedDelayString = "${app.map.cluster-snapshot.poll-interval-ms:5000}")
    public synchronized void synchronizeSnapshot() {
        if (!isEnabled()) { current = null; return; }
        try {
            if (!readFreshSharedSnapshot()) rebuildIfNeeded();
        } catch (Exception exception) {
            failures.increment();
            log.warn("지도 snapshot 갱신 실패, 유효한 기존 집계 또는 DB 조회 사용: {}", exception.getClass().getSimpleName());
        }
    }

    private boolean readFreshSharedSnapshot() throws Exception {
        List<Object> metadata = redis.opsForHash().multiGet(KEY, List.of("version", "createdAt"));
        if (metadata.size() != 2 || metadata.get(0) == null || metadata.get(1) == null) return false;
        long createdAt;
        try { createdAt = Long.parseLong(metadata.get(1).toString()); }
        catch (NumberFormatException invalid) { return false; }
        if (!fresh(createdAt, properties.getRefreshIntervalMs())) return false;
        MapClusterSnapshot snapshot = current;
        if (snapshot != null && snapshot.data.version().equals(metadata.get(0))) return true;
        String json = redis.execute(READ, List.of(KEY), Integer.toString(properties.getMaxPayloadBytes()));
        if (json == null) return false;
        MapClusterSnapshot.Data data = mapper.readValue(json, MapClusterSnapshot.Data.class);
        if (!fresh(data.createdAt(), properties.getRefreshIntervalMs())) return false;
        install(new MapClusterSnapshot(data, json.getBytes(StandardCharsets.UTF_8).length,
                properties.getMaxRegions(), properties.getMaxCategoryCounts()));
        return true;
    }

    private void rebuildIfNeeded() throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                // MV 갱신과 동일한 advisory lock. 게시가 끝날 때까지 유지해 오래된 게시자의 역전을 막는다.
                try (PreparedStatement lock = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
                    lock.setQueryTimeout(properties.getQueryTimeoutSeconds());
                    lock.setLong(1, MapRegionStoreSummaryRefreshService.REFRESH_LOCK_KEY);
                    try (ResultSet result = lock.executeQuery()) {
                        if (!result.next() || !result.getBoolean(1)) return;
                    }
                }
                if (!readFreshSharedSnapshot()) {
                    long createdAt = clock.millis();
                    List<MapClusterSnapshot.Region> rows = new ArrayList<>();
                    int counts = 0;
                    try (PreparedStatement statement = connection.prepareStatement(SQL)) {
                        statement.setQueryTimeout(properties.getQueryTimeoutSeconds());
                        statement.setInt(1, properties.getMaxRegions() + 1);
                        try (ResultSet result = statement.executeQuery()) {
                            while (result.next()) {
                                if (rows.size() >= properties.getMaxRegions()) throw new IllegalArgumentException("지도 구역 상한 초과");
                                String jsonCounts = result.getString("counts");
                                if (jsonCounts.length() > properties.getMaxPayloadBytes()) throw new IllegalArgumentException("지도 category 상한 초과");
                                Map<String, MapClusterSnapshot.CategoryCount> categories = mapper.readValue(jsonCounts, COUNTS);
                                counts += categories.size();
                                if (counts > properties.getMaxCategoryCounts()) throw new IllegalArgumentException("지도 category 수 상한 초과");
                                rows.add(new MapClusterSnapshot.Region(result.getString("aggregation_unit"),
                                        result.getString("region_type"), result.getString("region_hash"), result.getString("region_key"),
                                        result.getString("name"), result.getDouble("latitude"), result.getDouble("longitude"),
                                        result.getLong("total"), categories));
                            }
                        }
                    }
                    MapClusterSnapshot.Data data = new MapClusterSnapshot.Data(1, UUID.randomUUID().toString(), createdAt, rows);
                    String json = mapper.writeValueAsString(data);
                    int bytes = json.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > properties.getMaxPayloadBytes()) throw new IllegalArgumentException("지도 snapshot 크기 상한 초과");
                    MapClusterSnapshot snapshot = new MapClusterSnapshot(data, bytes, properties.getMaxRegions(), properties.getMaxCategoryCounts());
                    if (!fresh(createdAt, properties.getRefreshIntervalMs())) throw new IllegalStateException("지도 snapshot 생성 시간 초과");
                    // timeout 뒤 지연 도착한 이전 게시도 새 집계를 덮어쓰지 못하게 Redis에서 시각을 비교한다.
                    Long published = redis.execute(PUBLISH, List.of(KEY), Long.toString(createdAt), data.version(), json);
                    if (Long.valueOf(1).equals(published)) {
                        install(snapshot);
                        builds.increment();
                    } else {
                        readFreshSharedSnapshot();
                    }
                }
                connection.commit();
            } finally {
                // 중도 반환/실패 때도 transaction advisory lock을 해제한다.
                connection.rollback();
            }
        }
    }

    private void install(MapClusterSnapshot snapshot) {
        current = snapshot;
        installs.increment();
    }

    private boolean fresh(long createdAt, long maximumAge) {
        long age = clock.millis() - createdAt;
        return age >= -5_000 && age < maximumAge;
    }
}
