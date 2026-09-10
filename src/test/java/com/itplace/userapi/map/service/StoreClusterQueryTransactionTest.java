package com.itplace.userapi.map.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.itplace.userapi.map.repository.StoreRepository;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class StoreClusterQueryTransactionTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4-alpine")
                    .asCompatibleSubstituteFor("postgres"));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void customPlanAppliesToJpaConnectionAndResetsAfterCommitOrRollback(boolean failQuery) {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
            dataSource.setUsername(POSTGRES.getUsername());
            dataSource.setPassword(POSTGRES.getPassword());
            dataSource.setMaximumPoolSize(1);

            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan("com.itplace.userapi.map.service");
            factory.afterPropertiesSet();
            try {
                EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
                TransactionTemplate transaction = new TransactionTemplate(new JpaTransactionManager(factory.getObject()));
                transaction.setReadOnly(true);
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                Integer backendPid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                assertThat(jdbc.queryForObject("SHOW plan_cache_mode", String.class)).isEqualTo("auto");

                StoreRepository repository = mock(StoreRepository.class);
                when(repository.findStoreClustersInView(
                        anyDouble(), anyDouble(), anyDouble(), anyDouble(), isNull(), anyInt(), anyString()
                )).thenAnswer(invocation -> {
                    assertThat(entityManager.createNativeQuery("SHOW plan_cache_mode").getSingleResult())
                            .isEqualTo("force_custom_plan");
                    assertThat(entityManager.createNativeQuery("SHOW transaction_read_only").getSingleResult())
                            .isEqualTo("on");
                    assertThat(entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult())
                            .isEqualTo(backendPid);
                    if (failQuery) {
                        throw new IllegalStateException("query failed");
                    }
                    return List.of();
                });
                StoreClusterQueryService service = new StoreClusterQueryService(repository, entityManager);
                Runnable query = () -> transaction.executeWithoutResult(status ->
                        assertThat(service.findStoreClustersInView(37.49, 37.52, 126.99, 127.02, null, 7, "TOWN"))
                                .isEmpty());
                if (failQuery) {
                    assertThatThrownBy(query::run).isInstanceOf(IllegalStateException.class).hasMessage("query failed");
                } else {
                    query.run();
                }
                assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(backendPid);
                assertThat(jdbc.queryForObject("SHOW plan_cache_mode", String.class)).isEqualTo("auto");
            } finally {
                factory.destroy();
            }
        }
    }
}
