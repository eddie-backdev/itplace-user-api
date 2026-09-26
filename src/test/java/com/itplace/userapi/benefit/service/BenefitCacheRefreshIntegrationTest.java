package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.itplace.userapi.map.service.PartnerBenefitCacheService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class BenefitCacheRefreshIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private PartnerBenefitCacheService cache;
    private BenefitCacheRefreshService service;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        cache = mock(PartnerBenefitCacheService.class);
        service = new BenefitCacheRefreshService(jdbc, transactions, cache);
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
        jdbc.execute("""
                CREATE TABLE partner(partnerId BIGINT PRIMARY KEY);
                CREATE TABLE benefit(benefitId BIGINT PRIMARY KEY, partnerId BIGINT, benefitName TEXT, active BOOLEAN);
                CREATE TABLE benefitCarrierPolicy(benefitCarrierPolicyId BIGINT PRIMARY KEY,
                    benefitId BIGINT REFERENCES benefit, manual TEXT, active BOOLEAN, lastCrawledAt TIMESTAMP, lastModifiedDate TIMESTAMP);
                CREATE TABLE carrierTierBenefit(carrierTierBenefitId BIGINT PRIMARY KEY,
                    benefitCarrierPolicyId BIGINT REFERENCES benefitCarrierPolicy ON DELETE CASCADE, context TEXT);
                INSERT INTO partner VALUES (1),(2),(3);
                INSERT INTO benefit VALUES (10,1,'기존 혜택',true),(20,2,'다른 혜택',true);
                INSERT INTO benefitCarrierPolicy VALUES (100,10,'이용 안내',true,NOW(),NOW());
                INSERT INTO carrierTierBenefit VALUES (1000,100,'10% 할인');
                """);
        var migration = new ClassPathResource("db/migration/V20260926_0001__track_benefit_cache_changes.sql");
        jdbc.execute(migration.getContentAsString(StandardCharsets.UTF_8));
        assertThat(pending()).containsExactly(1L, 2L, 3L);
        jdbc.update("DELETE FROM benefit_cache_refresh");
    }

    @Test
    void capturesManualTierBulkAndDeletionChangesButIgnoresUnchangedRecrawl() {
        jdbc.update("UPDATE benefitCarrierPolicy SET lastCrawledAt=NOW(),lastModifiedDate=NOW(),manual=manual");
        assertThat(pending()).isEmpty();
        jdbc.update("UPDATE benefitCarrierPolicy SET manual='새 이용 안내' WHERE benefitCarrierPolicyId=100");
        assertThat(pending()).containsExactly(1L);
        assertThat(service.refreshPending()).isEqualTo(1);
        verify(cache).refreshBenefits(List.of(1L));
        jdbc.update("UPDATE carrierTierBenefit SET context='20% 할인' WHERE carrierTierBenefitId=1000");
        assertThat(pending()).containsExactly(1L);
        service.refreshPending();
        jdbc.update("UPDATE benefitCarrierPolicy SET active=false WHERE active=true");
        assertThat(pending()).containsExactly(1L);
        service.refreshPending();
        jdbc.update("DELETE FROM benefitCarrierPolicy WHERE benefitCarrierPolicyId=100");
        assertThat(pending()).containsExactly(1L);
        service.refreshPending();
        jdbc.update("DELETE FROM benefit WHERE benefitId=10");
        assertThat(pending()).containsExactly(1L);
    }

    @Test
    void movingBenefitRefreshesBothPartnersAndRollbackDoesNotPublish() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE benefit SET partnerId=3 WHERE benefitId=10");
            assertThat(pending()).containsExactly(1L, 3L);
            service.refreshAfterCommit();
            status.setRollbackOnly();
        });
        assertThat(pending()).isEmpty();
        verifyNoInteractions(cache);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("UPDATE benefit SET partnerId=3 WHERE benefitId=10");
            service.refreshAfterCommit();
        });
        verify(cache).refreshBenefits(List.of(1L, 3L));
        assertThat(pending()).isEmpty();
    }

    @Test
    void failedRedisRefreshRemainsDurableAndRetryCompletesIt() {
        jdbc.update("UPDATE benefit SET benefitName='새 혜택' WHERE benefitId=10");
        doThrow(new IllegalStateException("Redis unavailable")).doNothing().when(cache).refreshBenefits(anyList());
        assertThatThrownBy(service::refreshPending).hasMessage("Redis unavailable");
        assertThat(pending()).containsExactly(1L);
        // 새 프로세스에서도 같은 DB 대기열로 복구한다.
        assertThat(new BenefitCacheRefreshService(jdbc, transactions, cache).refreshPending()).isEqualTo(1);
        assertThat(pending()).isEmpty();
        verify(cache, times(2)).refreshBenefits(List.of(1L));
    }

    @Test
    void updateDuringRefreshIsNotLostWhenPendingRowIsAcknowledged() throws Exception {
        jdbc.update("UPDATE benefit SET benefitName='변경 1' WHERE benefitId=10");
        var refreshing = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        doAnswer(call -> {
            refreshing.countDown();
            assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(cache).refreshBenefits(anyList());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var refresh = pool.submit(service::refreshPending);
            assertThat(refreshing.await(5, TimeUnit.SECONDS)).isTrue();
            var update = pool.submit(() -> jdbc.update("UPDATE benefit SET benefitName='변경 2' WHERE benefitId=10"));
            finish.countDown();
            assertThat(refresh.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(update.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(pending()).containsExactly(1L);
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    private List<Long> pending() {
        return jdbc.queryForList("SELECT partner_id FROM benefit_cache_refresh ORDER BY partner_id", Long.class);
    }
}
