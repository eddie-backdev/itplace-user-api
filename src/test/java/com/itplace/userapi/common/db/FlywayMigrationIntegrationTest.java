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
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4-alpine")
                    .asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void createPreFlywaySchema() throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            statement.execute("""
                    CREATE TABLE partner (
                        partnerId BIGINT PRIMARY KEY,
                        partnerName VARCHAR(100),
                        category VARCHAR(100)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE store (
                        storeId BIGSERIAL PRIMARY KEY,
                        partnerId BIGINT NOT NULL,
                        storeName VARCHAR(255),
                        business VARCHAR(255),
                        address VARCHAR(512),
                        city VARCHAR(100),
                        town VARCHAR(100),
                        legalDong VARCHAR(100),
                        latitude DOUBLE PRECISION,
                        longitude DOUBLE PRECISION,
                        location geometry(Point, 4326)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE users (
                        userId BIGSERIAL PRIMARY KEY,
                        name VARCHAR(30),
                        password VARCHAR(255)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE socialAccount (
                        id BIGSERIAL PRIMARY KEY,
                        userId BIGINT NOT NULL,
                        provider VARCHAR(30) NOT NULL,
                        providerId VARCHAR(255) NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE benefitPolicy (
                        benefitPolicyId BIGSERIAL PRIMARY KEY
                    )
                    """);
            statement.execute("""
                    CREATE TABLE benefit (
                        benefitId BIGSERIAL PRIMARY KEY,
                        partnerId BIGINT NOT NULL,
                        mainCategory VARCHAR(100),
                        benefitName VARCHAR(512),
                        active BOOLEAN,
                        carrier VARCHAR(10),
                        type VARCHAR(50),
                        description TEXT,
                        manual TEXT,
                        usageType VARCHAR(50),
                        url VARCHAR(512),
                        sourceKey VARCHAR(512),
                        sourceUrl VARCHAR(512),
                        sourceCategory VARCHAR(100),
                        lastCrawledAt TIMESTAMP,
                        benefitLimit BIGINT,
                        createdDate TIMESTAMP,
                        lastModifiedDate TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE benefitCarrierPolicy (
                        id BIGSERIAL PRIMARY KEY,
                        benefitId BIGINT NOT NULL,
                        carrier VARCHAR(16),
                        active BOOLEAN,
                        usageType VARCHAR(20),
                        lastCrawledAt TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE map_store_cluster_region (
                        store_id BIGINT PRIMARY KEY,
                        city_region_key VARCHAR(300),
                        city_region_name VARCHAR(100),
                        city_region_hash CHAR(32),
                        town_region_type VARCHAR(20),
                        town_region_key VARCHAR(300),
                        town_region_name VARCHAR(100),
                        town_region_hash CHAR(32),
                        legal_dong_region_type VARCHAR(20),
                        legal_dong_region_key VARCHAR(300),
                        legal_dong_region_name VARCHAR(100),
                        legal_dong_region_hash CHAR(32)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE map_region_anchor (
                        region_type VARCHAR(20) NOT NULL,
                        region_key VARCHAR(300) NOT NULL,
                        region_name VARCHAR(100),
                        latitude DOUBLE PRECISION NOT NULL,
                        longitude DOUBLE PRECISION NOT NULL,
                        PRIMARY KEY (region_type, region_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE recommendations (
                        id BIGSERIAL PRIMARY KEY,
                        userId BIGINT NOT NULL,
                        ranking INTEGER NOT NULL,
                        createdDate TIMESTAMP NOT NULL
                    )
                    """);
            statement.execute("CREATE TABLE favorite (benefitId BIGINT, userId BIGINT, createdDate TIMESTAMP, PRIMARY KEY(benefitId,userId))");
            statement.execute("INSERT INTO partner VALUES (1, '테스트카페', '카페')");
            statement.execute("""
                    INSERT INTO store (
                        partnerId, business, address, city, town, legalDong,
                        latitude, longitude, location
                    ) VALUES (
                        1, '카페', '서울특별시 강남구 역삼동', '서울', '강남구', '역삼동',
                        37.50, 127.04, ST_SetSRID(ST_MakePoint(127.04, 37.50), 4326)
                    )
                    """);
            statement.execute("INSERT INTO users (name, password) VALUES ('테스터', 'encoded-password')");
            statement.execute("INSERT INTO socialAccount (userId, provider, providerId) VALUES (1, 'KAKAO', 'kakao-1')");
            statement.execute("""
                    INSERT INTO benefit (
                        partnerId, mainCategory, benefitName, active, carrier, type,
                        description, manual, usageType, url, sourceKey, sourceUrl,
                        sourceCategory, lastCrawledAt, createdDate, lastModifiedDate
                    ) VALUES (
                        1, 'CAFE', '테스트 혜택', true, 'SKT', 'DISCOUNT',
                        '설명', '안내', 'offline', 'https://example.com', 'skt:test',
                        'https://source.example.com', '카페',
                        TIMESTAMP '2026-08-13 03:00:00', NOW(), NOW()
                    )
                    """);
            statement.execute("""
                    INSERT INTO benefitCarrierPolicy (
                        benefitId,
                        carrier,
                        active,
                        usageType,
                        lastCrawledAt
                    ) VALUES (1, 'SKT', true, 'offline', TIMESTAMP '2026-08-13 03:00:00')
                    """);
            statement.execute("""
                    INSERT INTO recommendations (userId, ranking, createdDate)
                    VALUES (1, 1, TIMESTAMP '2026-08-15 01:00:00')
                    """);
            statement.execute("""
                    INSERT INTO map_store_cluster_region VALUES (
                        1,
                        '서울', '서울', MD5('서울'),
                        'TOWN', '서울|강남구', '강남구', MD5('서울|강남구'),
                        'LEGAL_DONG', '서울|강남구|역삼동', '역삼동', MD5('서울|강남구|역삼동')
                    )
                    """);
            statement.execute("""
                    INSERT INTO map_region_anchor VALUES
                        ('CITY', '서울', '서울', 37.55, 126.98),
                        ('TOWN', '서울|강남구', '강남구', 37.50, 127.03),
                        ('LEGAL_DONG', '서울|강남구|역삼동', '역삼동', 37.50, 127.04)
                    """);
        }
    }

    @Test
    void baselineExistingSchemaAndApplyAllMigrations() throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion(MigrationVersion.fromVersion("20260722.0"))
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(11);

        try (Connection connection = POSTGRES.createConnection("")) {
            assertThat(columnExists(connection, "store", "active")).isTrue();
            assertThat(columnExists(connection, "store", "healthymisscount")).isTrue();
            assertThat(indexExists(connection, "uq_store_kakao_partner_place")).isTrue();
            assertThat(indexExists(connection, "uq_map_region_store_summary_lookup")).isTrue();
            assertThat(materializedViewExists(connection, "map_region_store_summary")).isTrue();
            assertThat(tableExists(connection, "benefitsnapshotimportstate")).isTrue();
            assertThat(tableExists(connection, "authcredential")).isTrue();
            assertThat(tableExists(connection, "inquiries")).isTrue();
            assertThat(tableExists(connection, "recommendation_rank_traces")).isTrue();
            assertThat(columnExists(connection, "users", "nickname")).isTrue();
            assertThat(columnExists(connection, "benefit", "carrier")).isFalse();
            assertThat(columnExists(connection, "recommendations", "cachebatchid")).isTrue();
            assertThat(columnExists(connection, "recommendations", "requestid")).isTrue();
            assertThat(indexExists(connection, "idx_store_location")).isTrue();
            assertThat(indexExists(connection, "idx_store_name_search")).isTrue();
            assertThat(indexExists(connection, "idx_favorite_user_created")).isTrue();
            assertThat(indexExists(connection, "idx_inquiries_status_created")).isTrue();
            assertThat(indexExists(connection, "idx_map_region_anchor_viewport")).isTrue();
            assertThat(indexExists(connection, "idx_map_region_store_summary_cluster_lookup")).isTrue();
            assertThat(longValue(connection, "SELECT COUNT(*) FROM authCredential")).isEqualTo(2L);
            assertThat(stringValue(connection, "SELECT nickname FROM users WHERE userId = 1"))
                    .isEqualTo("테스터");
            assertThat(stringValue(connection, "SELECT sourceUrl FROM benefitCarrierPolicy WHERE benefitId = 1"))
                    .isEqualTo("https://source.example.com");
            assertThat(booleanValue(connection, "SELECT active FROM recommendations WHERE id = 1")).isTrue();
            assertThat(longValue(connection, "SELECT COUNT(*) FROM benefitSnapshotImportState")).isEqualTo(3L);
            assertThat(stringValue(connection, """
                    SELECT lastAppliedCrawledAt::text
                    FROM benefitSnapshotImportState
                    WHERE carrier = 'SKT'
                    """)).isEqualTo("2026-08-13 03:00:00");
            assertThat(booleanValue(connection, "SELECT active FROM store WHERE storeId = 1")).isTrue();
            assertThat(longValue(connection, """
                    SELECT store_count
                    FROM map_region_store_summary
                    WHERE aggregation_unit = 'TOWN'
                      AND region_type = 'TOWN'
                      AND category = '카페'
                    """)).isEqualTo(1L);
            assertThat(stringValue(connection, """
                    SELECT version
                    FROM flyway_schema_history
                    WHERE success = true
                    ORDER BY installed_rank DESC
                    LIMIT 1
                    """)).isEqualTo("20260911.0001");

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE store SET active = false WHERE storeId = 1");
                statement.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY map_region_store_summary");
            }
            assertThat(longValue(connection, "SELECT COUNT(*) FROM map_region_store_summary")).isZero();
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

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'public'
                      AND table_name = ?
                )
                """)) {
            statement.setString(1, tableName);
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

    private boolean materializedViewExists(Connection connection, String viewName) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_matviews
                    WHERE schemaname = 'public'
                      AND matviewname = ?
                )
                """)) {
            statement.setString(1, viewName);
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

    private long longValue(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
