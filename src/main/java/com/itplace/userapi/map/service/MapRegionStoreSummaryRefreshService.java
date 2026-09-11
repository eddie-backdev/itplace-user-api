package com.itplace.userapi.map.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class MapRegionStoreSummaryRefreshService {

    static final long REFRESH_LOCK_KEY = 4_858_250_601L;

    private final DataSource sourceDataSource;

    @Value("${app.map.cluster-summary.refresh.enabled:true}")
    private boolean refreshEnabled;

    @Value("${app.map.cluster-summary.refresh.minimum-interval-ms:240000}")
    private long minimumIntervalMs;

    @Value("${app.map.cluster-summary.refresh.query-timeout-seconds:45}")
    private int queryTimeoutSeconds = 45;

    @Value("${app.map.cluster-summary.refresh.network-timeout-ms:50000}")
    private int networkTimeoutMs = 50_000;

    public MapRegionStoreSummaryRefreshService(
            @Qualifier("sourceDataSource") DataSource sourceDataSource
    ) {
        this.sourceDataSource = sourceDataSource;
    }

    @Scheduled(
            initialDelayString = "${app.map.cluster-summary.refresh.initial-delay-ms:60000}",
            fixedDelayString = "${app.map.cluster-summary.refresh.fixed-delay-ms:300000}",
            scheduler = "mapSummaryScheduler"
    )
    public void refreshIfStale() {
        if (!refreshEnabled) {
            return;
        }

        try (Connection connection = sourceDataSource.getConnection()) {
            int originalNetworkTimeout = connection.getNetworkTimeout();
            connection.setNetworkTimeout(Runnable::run, networkTimeoutMs);
            try {
                connection.setAutoCommit(true);
                if (!tryLock(connection)) {
                    log.debug("지도 클러스터 집계 갱신이 다른 인스턴스에서 진행 중입니다.");
                    return;
                }

                try {
                    if (!isRefreshDue(connection)) {
                        return;
                    }
                    long startedAt = System.nanoTime();
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(queryTimeoutSeconds);
                        statement.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY map_region_store_summary");
                        statement.execute("ANALYZE map_region_store_summary");
                        statement.executeUpdate("""
                                UPDATE map_region_store_summary_state
                                SET last_refreshed_at = CURRENT_TIMESTAMP
                                WHERE singleton = true
                                """);
                    }
                    log.info("지도 클러스터 집계 갱신 완료: elapsedMs={}",
                            (System.nanoTime() - startedAt) / 1_000_000L);
                } finally {
                    unlock(connection);
                }
            } finally {
                if (!connection.isClosed()) connection.setNetworkTimeout(Runnable::run, originalNetworkTimeout);
            }
        } catch (SQLException e) {
            log.warn("지도 클러스터 집계 갱신 실패: {}", e.getMessage());
        }
    }

    private boolean isRefreshDue(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(Math.min(queryTimeoutSeconds, 5));
            try (ResultSet result = statement.executeQuery("""
                     SELECT last_refreshed_at
                     FROM map_region_store_summary_state
                     WHERE singleton = true
                     """)) {
                if (!result.next()) {
                    return true;
                }
                Instant lastRefreshedAt = result.getTimestamp(1).toInstant();
                return !lastRefreshedAt.plusMillis(Math.max(minimumIntervalMs, 0L)).isAfter(Instant.now());
            }
        }
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setQueryTimeout(Math.min(queryTimeoutSeconds, 5));
            statement.setLong(1, REFRESH_LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void unlock(Connection connection) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            statement.setQueryTimeout(Math.min(queryTimeoutSeconds, 5));
            statement.setLong(1, REFRESH_LOCK_KEY);
            statement.executeQuery();
        } catch (SQLException e) {
            // Session advisory lock이 남은 connection을 pool에 되돌리지 않는다.
            try { connection.abort(Runnable::run); }
            catch (SQLException abortFailure) { e.addSuppressed(abortFailure); }
            log.warn("지도 클러스터 집계 갱신 잠금 해제 실패: {}", e.getMessage());
        }
    }
}
