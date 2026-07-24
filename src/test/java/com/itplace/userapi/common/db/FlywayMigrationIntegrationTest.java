package com.itplace.userapi.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void createPreFlywaySchema() throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE store (
                        storeId BIGSERIAL PRIMARY KEY,
                        partnerId BIGINT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE benefit (
                        benefitId BIGSERIAL PRIMARY KEY,
                        partnerId BIGINT NOT NULL,
                        active BOOLEAN
                    )
                    """);
            statement.execute("""
                    CREATE TABLE benefitCarrierPolicy (
                        id BIGSERIAL PRIMARY KEY,
                        benefitId BIGINT NOT NULL,
                        active BOOLEAN,
                        usageType VARCHAR(20)
                    )
                    """);
            statement.execute("INSERT INTO store (partnerId) VALUES (1)");
        }
    }

    @Test
    void baselineExistingSchemaAndApplyStoreLifecycleMigration() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("20260722.0"))
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);

        try (Connection connection = POSTGRES.createConnection("")) {
            assertThat(columnExists(connection, "store", "active")).isTrue();
            assertThat(columnExists(connection, "store", "healthymisscount")).isTrue();
            assertThat(indexExists(connection, "uq_store_kakao_partner_place")).isTrue();
            assertThat(booleanValue(connection, "SELECT active FROM store WHERE storeId = 1")).isTrue();
            assertThat(stringValue(connection, """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)).isEqualTo("20260722.2326");
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = ?
                      AND column_name = ?
                )
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND indexname = ?
                )
                """)) {
            statement.setString(1, indexName);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private boolean booleanValue(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private String stringValue(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
