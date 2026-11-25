package com.itplace.userapi.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AuthGrantConsumptionStoreIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static AuthGrantConsumptionStore store;
    static TransactionTemplate transaction;

    @BeforeAll
    static void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261125_0001__track_auth_grant_consumption.sql"))
                .execute(dataSource);
        jdbc.execute("CREATE TABLE test_password (user_id BIGINT PRIMARY KEY, password_hash TEXT NOT NULL)");
        jdbc.update("INSERT INTO test_password VALUES (1, 'old-hash')");
        store = new AuthGrantConsumptionStore(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM auth_grant_consumption");
        jdbc.update("UPDATE test_password SET password_hash = 'old-hash' WHERE user_id = 1");
    }

    @Test
    void concurrentClaimsUpdatePasswordOnlyOnce() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Boolean>> attempts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String password = "hash-" + i;
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
                    return transaction.execute(status -> {
                        if (!store.claim("resetPassword:same-reset-token", Duration.ofMinutes(5))) return false;
                        jdbc.update("UPDATE test_password SET password_hash = ? WHERE user_id = 1", password);
                        return true;
                    });
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) successes++;

            assertThat(successes).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT password_hash FROM test_password WHERE user_id = 1", String.class))
                    .startsWith("hash-");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_grant_consumption", Long.class))
                    .isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rollbackRestoresBothPasswordAndTokenAvailability() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(store.claim("resetPassword:retryable-token", Duration.ofMinutes(5))).isTrue();
            jdbc.update("UPDATE test_password SET password_hash = 'new-hash' WHERE user_id = 1");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT password_hash FROM test_password WHERE user_id = 1", String.class))
                .isEqualTo("old-hash");
        Boolean retrySucceeded = transaction.execute(
                status -> store.claim("resetPassword:retryable-token", Duration.ofMinutes(5)));
        assertThat(retrySucceeded).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_grant_consumption", Long.class))
                .isEqualTo(1L);
    }
}
