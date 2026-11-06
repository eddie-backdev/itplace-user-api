package com.itplace.userapi.map.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MapPartnerViewRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static MapPartnerViewRepository repository;

    @BeforeEach
    void clearEvents() {
        jdbc.update("DELETE FROM map_partner_view_daily");
    }

    @BeforeAll
    static void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE partner (partnerId BIGINT PRIMARY KEY);
                CREATE TABLE users (userId BIGINT PRIMARY KEY);
                CREATE TABLE store (storeId BIGINT PRIMARY KEY, partnerId BIGINT NOT NULL, active BOOLEAN NOT NULL);
                CREATE TABLE benefit (benefitId BIGINT PRIMARY KEY, partnerId BIGINT NOT NULL, active BOOLEAN);
                CREATE TABLE benefitCarrierPolicy (benefitCarrierPolicyId BIGINT PRIMARY KEY,
                    benefitId BIGINT NOT NULL, active BOOLEAN, publicationStatus VARCHAR(32));
                INSERT INTO partner VALUES (1), (2);
                INSERT INTO users VALUES (7);
                INSERT INTO store VALUES (101, 1, true), (102, 1, true), (103, 1, false), (201, 2, true);
                INSERT INTO benefit VALUES (301, 1, true), (302, 2, true), (303, 2, true);
                INSERT INTO benefitCarrierPolicy VALUES
                    (401, 301, true, 'APPROVED'), (402, 302, true, 'PENDING'), (403, 303, false, 'APPROVED');
                """);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261107_0001__track_daily_map_partner_views.sql")).execute(dataSource);
        repository = new MapPartnerViewRepository(jdbc);
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void deduplicatesUserAndSessionAcrossStoresForTheSamePartnerAndDay() {
        LocalDate today = LocalDate.now();
        assertThat(repository.recordIfEligible(101L, 7L, null, today)).isEqualTo(1);
        assertThat(repository.recordIfEligible(102L, 7L, null, today)).isZero();
        assertThat(repository.recordIfEligible(101L, null, "a".repeat(64), today)).isEqualTo(1);
        assertThat(repository.recordIfEligible(102L, null, "a".repeat(64), today)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM map_partner_view_daily", Long.class)).isEqualTo(2L);
    }

    @Test
    void onlyInsertsWhenStoreAndActiveApprovedBenefitAreEligible() {
        LocalDate today = LocalDate.now();
        assertThat(repository.recordIfEligible(103L, 7L, null, today)).isZero();
        assertThat(repository.recordIfEligible(201L, 7L, null, today)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM map_partner_view_daily", Long.class)).isZero();
    }

    @Test
    void concurrentDuplicateSubmissionsProduceExactlyOneRow() throws Exception {
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<java.util.concurrent.Future<Integer>> writes = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                writes.add(executor.submit(() -> repository.recordIfEligible(101L, 7L, null,
                        LocalDate.now())));
            }
            int inserted = 0;
            for (var write : writes) inserted += write.get(10, TimeUnit.SECONDS);
            assertThat(inserted).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM map_partner_view_daily", Long.class)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }
}
