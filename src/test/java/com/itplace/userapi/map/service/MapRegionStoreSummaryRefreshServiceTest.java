package com.itplace.userapi.map.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLTimeoutException;
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
        verify(connection).setNetworkTimeout(any(), eq(50_000));
        verify(connection).setNetworkTimeout(any(), eq(0));
        verify(refreshStatement).setQueryTimeout(45);
        verify(stateStatement).setQueryTimeout(5);
        verify(lockStatement).setQueryTimeout(5);
        verify(unlockStatement).setQueryTimeout(5);
        verify(lockStatement).setLong(1, REFRESH_LOCK_KEY);
        verify(refreshStatement).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY map_region_store_summary");
        verify(refreshStatement).execute("ANALYZE map_region_store_summary");
        verify(refreshStatement).executeUpdate(org.mockito.ArgumentMatchers.contains("last_refreshed_at"));
        verify(unlockStatement).setLong(1, REFRESH_LOCK_KEY);
        verify(unlockStatement).executeQuery();
    }

    @Test
    void refreshTimeoutDoesNotMarkSummaryFreshAndStillReleasesLock() throws Exception {
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
        doThrow(new SQLTimeoutException("refresh timed out")).when(refreshStatement)
                .execute("REFRESH MATERIALIZED VIEW CONCURRENTLY map_region_store_summary");

        service.refreshIfStale();

        verify(refreshStatement, never()).executeUpdate(org.mockito.ArgumentMatchers.anyString());
        verify(unlockStatement).executeQuery();
        verify(connection).setNetworkTimeout(any(), eq(0));
    }

    @Test
    void failedUnlockAbortsConnectionInsteadOfReturningHeldSessionLockToPool() throws Exception {
        when(sourceDataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?)")).thenReturn(lockStatement);
        when(lockStatement.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);
        when(connection.createStatement()).thenReturn(stateStatement);
        when(stateStatement.executeQuery(org.mockito.ArgumentMatchers.anyString())).thenThrow(new SQLTimeoutException());
        when(connection.prepareStatement("SELECT pg_advisory_unlock(?)")).thenReturn(unlockStatement);
        when(unlockStatement.executeQuery()).thenThrow(new SQLTimeoutException());

        service.refreshIfStale();

        verify(connection).abort(any());
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
