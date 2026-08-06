package com.itplace.userapi.map.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MapRegionStoreSummaryRefreshServiceTest {

    private static final long REFRESH_LOCK_KEY = 4_858_250_601L;

    @Mock
    private DataSource sourceDataSource;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement lockStatement;

    @Mock
    private PreparedStatement unlockStatement;

    @Mock
    private ResultSet lockResult;

    @Mock
    private ResultSet stateResult;

    @Mock
    private Statement stateStatement;

    @Mock
    private Statement refreshStatement;

    private MapRegionStoreSummaryRefreshService service;

    @BeforeEach
    void setUp() {
        service = new MapRegionStoreSummaryRefreshService(sourceDataSource);
        ReflectionTestUtils.setField(service, "refreshEnabled", true);
        ReflectionTestUtils.setField(service, "minimumIntervalMs", 240_000L);
    }

    @Test
    void refreshesStaleSummaryOnSourceDataSourceAndReleasesLock() throws Exception {
        when(sourceDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);
        when(connection.createStatement()).thenReturn(stateStatement, refreshStatement);
        when(stateStatement.executeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(stateResult);
        when(stateResult.next()).thenReturn(true);
        when(stateResult.getTimestamp(1)).thenReturn(Timestamp.from(Instant.EPOCH));
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlockStatement);

        service.refreshIfStale();

        verify(connection).setAutoCommit(true);
        verify(lockStatement).setLong(1, REFRESH_LOCK_KEY);
        verify(refreshStatement).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY map_region_store_summary");
        verify(refreshStatement).execute("ANALYZE map_region_store_summary");
        verify(refreshStatement).executeUpdate(org.mockito.ArgumentMatchers.contains("last_refreshed_at"));
        verify(unlockStatement).setLong(1, REFRESH_LOCK_KEY);
        verify(unlockStatement).executeQuery();
    }

    @Test
    void skipsRefreshWhenAnotherInstanceOwnsTheLock() throws Exception {
        when(sourceDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(false);

        service.refreshIfStale();

        verify(connection, never()).createStatement();
        verify(connection, never()).prepareStatement("SELECT pg_advisory_unlock(?)");
    }
}
