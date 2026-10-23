package com.itplace.userapi.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LegacyMembershipCleanupMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void removesEmptyLegacyObjectsAndPreservesCurrentData() throws Exception {
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("""
                    CREATE TABLE users (
                        userId BIGINT PRIMARY KEY,
                        carrier TEXT,
                        membershipGradeCode TEXT,
                        membershipVerified BOOLEAN,
                        membershipid TEXT,
                        "membershipId" BIGINT
                    );
                    INSERT INTO users VALUES (1, 'SKT', 'GOLD', true, '  ', NULL);
                    CREATE TABLE membership (id BIGINT PRIMARY KEY);
                    CREATE TABLE membershiphistory (id BIGINT PRIMARY KEY, membership_id BIGINT REFERENCES membership);
                    CREATE TABLE uplusdata (id BIGINT PRIMARY KEY);
                    CREATE TABLE benefitUsageRecord (id BIGINT PRIMARY KEY, userId BIGINT);
                    INSERT INTO benefitUsageRecord VALUES (7, 1);
                    """);

            migrate(statement);

            assertThat(exists(statement, "SELECT to_regclass('membership') IS NOT NULL")).isFalse();
            assertThat(exists(statement, "SELECT to_regclass('membershiphistory') IS NOT NULL")).isFalse();
            assertThat(exists(statement, "SELECT to_regclass('uplusdata') IS NOT NULL")).isFalse();
            assertThat(exists(statement, "SELECT EXISTS (SELECT FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='users' AND column_name IN ('membershipid','membershipId'))")).isFalse();
            assertThat(value(statement, "SELECT carrier || ':' || membershipGradeCode || ':' || membershipVerified FROM users WHERE userId=1"))
                    .isEqualTo("SKT:GOLD:true");
            assertThat(value(statement, "SELECT count(*) FROM benefitUsageRecord WHERE id=7 AND userId=1"))
                    .isEqualTo("1");
            connection.rollback();
        }
    }

    @Test
    void refusesPopulatedLegacyTablesWithoutPartialChanges() throws Exception {
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (String table : new String[]{"membershiphistory", "membership", "uplusdata"}) {
                String schema = "legacy_guard_" + table;
                useSchema(statement, schema);
                statement.execute("CREATE TABLE " + table + " (id BIGINT); INSERT INTO " + table + " VALUES (1)");
                connection.commit();

                assertThatThrownBy(() -> migrate(statement))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("Refusing to remove legacy table " + schema + "." + table);
                connection.rollback();

                assertThat(exists(statement, "SELECT to_regclass('" + schema + "." + table + "') IS NOT NULL")).isTrue();
                assertThat(value(statement, "SELECT count(*) FROM " + schema + "." + table)).isEqualTo("1");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
                connection.commit();
            }
        }
    }

    @Test
    void refusesNonblankValuesInEitherLegacyUserNumberColumnWithoutPartialChanges() throws Exception {
        try (Connection connection = POSTGRES.createConnection("");
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            for (String legacyColumn : new String[]{"membershipid", "membershipId"}) {
                String schema = "legacy_user_guard_" + (legacyColumn.equals("membershipid") ? "unquoted" : "quoted");
                useSchema(statement, schema);
                statement.execute("CREATE TABLE users (userId BIGINT, membershipid TEXT, \"membershipId\" BIGINT)");
                if (legacyColumn.equals("membershipid")) {
                    statement.execute("INSERT INTO users VALUES (1, 'legacy-42', NULL)");
                } else {
                    statement.execute("INSERT INTO users VALUES (1, NULL, 42)");
                }
                statement.execute("CREATE TABLE membership (id BIGINT)");
                connection.commit();

                assertThatThrownBy(() -> migrate(statement))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("Refusing to remove users." + legacyColumn);
                connection.rollback();

                assertThat(exists(statement, "SELECT to_regclass('" + schema + ".membership') IS NOT NULL")).isTrue();
                assertThat(exists(statement, "SELECT EXISTS (SELECT FROM information_schema.columns WHERE table_schema='" + schema + "' AND table_name='users' AND column_name='" + legacyColumn + "')")).isTrue();
                assertThat(value(statement, "SELECT " + (legacyColumn.equals("membershipid") ? "membershipid::text" : "\"membershipId\"::text") + " FROM " + schema + ".users WHERE userId=1"))
                        .isEqualTo(legacyColumn.equals("membershipid") ? "legacy-42" : "42");
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
                connection.commit();
            }
        }
    }

    private static void migrate(Statement statement) throws Exception {
        try (var stream = LegacyMembershipCleanupMigrationIntegrationTest.class
                .getResourceAsStream("/db/migration/V20261021_0002__remove_legacy_membership_schema.sql")) {
            if (stream == null) throw new IllegalStateException("Missing legacy membership cleanup migration");
            statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static boolean exists(Statement statement, String query) throws SQLException {
        try (var result = statement.executeQuery(query)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private static void useSchema(Statement statement, String schema) throws SQLException {
        statement.execute("CREATE SCHEMA " + schema);
        statement.execute("SET search_path TO " + schema);
    }

    private static String value(Statement statement, String query) throws SQLException {
        try (var result = statement.executeQuery(query)) {
            result.next();
            return result.getString(1);
        }
    }
}
