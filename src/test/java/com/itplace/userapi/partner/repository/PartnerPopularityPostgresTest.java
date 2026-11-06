package com.itplace.userapi.partner.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PartnerPopularityPostgresTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 7);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static HikariDataSource dataSource;
    static NamedParameterJdbcTemplate jdbc;
    static Query partnerQuery;
    static Query popularityCountsQuery;

    @BeforeAll
    static void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcTemplate template = new JdbcTemplate(dataSource);
        jdbc = new NamedParameterJdbcTemplate(template);

        template.execute("""
                CREATE TABLE partner (partnerId BIGINT PRIMARY KEY, partnerName TEXT, category TEXT);
                CREATE TABLE users (userId BIGINT PRIMARY KEY);
                CREATE TABLE partner_category (code TEXT PRIMARY KEY, label TEXT);
                CREATE TABLE partner_category_alias (carrier_code TEXT, source_label TEXT, category_code TEXT);
                CREATE TABLE partnerAlias (partnerAliasId BIGINT PRIMARY KEY, partnerId BIGINT,
                    aliasName TEXT, normalizedAlias TEXT, source TEXT);
                CREATE TABLE benefit (benefitId BIGINT PRIMARY KEY, partnerId BIGINT, benefitName TEXT,
                    mainCategory TEXT, active BOOLEAN);
                CREATE TABLE benefitCarrierPolicy (benefitCarrierPolicyId BIGINT PRIMARY KEY,
                    benefitId BIGINT, carrier TEXT, usageType TEXT, active BOOLEAN, publicationStatus TEXT,
                    conditions JSONB, description TEXT, sourceCategory TEXT);
                CREATE TABLE carrierTierBenefit (benefitCarrierPolicyId BIGINT, grade TEXT, context TEXT, offerKey TEXT);
                CREATE TABLE membership_grade_alias (carrier_code TEXT, source_label TEXT, grade_code TEXT);
                CREATE TABLE favorite (benefitId BIGINT NOT NULL);
                """);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V20261107_0001__track_daily_map_partner_views.sql")).execute(dataSource);

        partnerQuery = query("findBenefitPartners");
        popularityCountsQuery = query("findPopularityCounts");
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void nativePopularityOrderAndCountsUseTheSamePublicEligibilityAndSevenDayWindow() {
        seedPartnersAndBenefits();
        seedViews(1L, TODAY.minusDays(6), TODAY.minusDays(2), TODAY,
                TODAY.minusDays(7), TODAY.plusDays(1));
        seedViews(2L, TODAY.minusDays(1), TODAY);
        seedViews(3L, TODAY.minusDays(2), TODAY);
        seedViews(4L, TODAY.minusDays(1), TODAY);
        jdbc.getJdbcTemplate().execute("""
                INSERT INTO favorite (benefitId) VALUES
                    (102), (102), (103), (103), (104), (104), (104), (104),
                    (202), (202), (202), (202),
                    (302), (302), (302), (302),
                    (402), (402), (402), (402),
                    (502), (502),
                    (802), (802), (802),
                    (902), (902), (902)
                """);

        MapSqlParameterSource params = popularityParams();
        List<Long> partnerIds = jdbc.query(partnerQuery.value(), params,
                (rs, row) -> rs.getLong("partnerId"));
        assertThat(partnerIds).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(partnerIds).contains(6L, 7L); // Public partners with no map views remain listed.

        Map<Long, List<Long>> counts = jdbc.query(popularityCountsQuery.value(),
                        new MapSqlParameterSource()
                                .addValue("partnerIds", partnerIds)
                                .addValue("viewFromDate", Date.valueOf(TODAY.minusDays(6)))
                                .addValue("viewThroughDate", Date.valueOf(TODAY)),
                        (rs, row) -> List.of(rs.getLong("partnerId"), rs.getLong("mapViewCount7d"),
                                rs.getLong("favoriteCount")))
                .stream().collect(Collectors.toMap(row -> row.get(0), row -> row));

        assertThat(counts).containsEntry(1L, List.of(1L, 3L, 0L));
        assertThat(counts).containsEntry(2L, List.of(2L, 2L, 4L));
        assertThat(counts).containsEntry(3L, List.of(3L, 2L, 4L));
        assertThat(counts).containsEntry(4L, List.of(4L, 2L, 4L));
        assertThat(counts).containsEntry(5L, List.of(5L, 0L, 2L));
        assertThat(counts).containsEntry(6L, List.of(6L, 0L, 0L));
        assertThat(counts).containsEntry(7L, List.of(7L, 0L, 0L));
        assertThat(counts).doesNotContainKeys(8L, 9L);

        assertThat(jdbc.queryForObject(partnerQuery.countQuery(), params, Long.class)).isEqualTo(7L);
    }

    private static void seedPartnersAndBenefits() {
        jdbc.getJdbcTemplate().execute("""
                INSERT INTO partner (partnerId, partnerName, category) VALUES
                    (1, '지도 리더', '카페'), (2, 'A 동률', '카페'), (3, '동일 이름', '카페'),
                    (4, '동일 이름', '카페'), (5, '관심만 있음', '카페'), (6, '무조회 A', '카페'),
                    (7, '무조회 B', '카페'), (8, '비활성 제휴처', '카페'), (9, '미승인 제휴처', '카페');
                INSERT INTO benefit (benefitId, partnerId, benefitName, mainCategory, active) VALUES
                    (101, 1, '유효 혜택', 'BASIC_BENEFIT', true),
                    (102, 1, '비활성 정책 혜택', 'BASIC_BENEFIT', true),
                    (103, 1, '미승인 정책 혜택', 'BASIC_BENEFIT', true),
                    (104, 1, '비활성 혜택', 'BASIC_BENEFIT', false),
                    (202, 2, '혜택', 'BASIC_BENEFIT', true), (302, 3, '혜택', 'BASIC_BENEFIT', true),
                    (402, 4, '혜택', 'BASIC_BENEFIT', true), (502, 5, '혜택', 'BASIC_BENEFIT', true),
                    (602, 6, '혜택', 'BASIC_BENEFIT', true), (702, 7, '혜택', 'BASIC_BENEFIT', true),
                    (802, 8, '비활성 혜택', 'BASIC_BENEFIT', false),
                    (902, 9, '미승인 혜택', 'BASIC_BENEFIT', true);
                INSERT INTO benefitCarrierPolicy
                    (benefitCarrierPolicyId, benefitId, carrier, usageType, active, publicationStatus)
                VALUES
                    (1001, 101, 'SKT', 'both', true, 'APPROVED'),
                    (1002, 102, 'SKT', 'both', false, 'APPROVED'),
                    (1003, 103, 'SKT', 'both', true, 'PENDING'),
                    (1004, 104, 'SKT', 'both', true, 'APPROVED'),
                    (2002, 202, 'SKT', 'both', true, 'APPROVED'),
                    (3002, 302, 'SKT', 'both', true, 'APPROVED'),
                    (4002, 402, 'SKT', 'both', true, 'APPROVED'),
                    (5002, 502, 'SKT', 'both', true, 'APPROVED'),
                    (6002, 602, 'SKT', 'both', true, 'APPROVED'),
                    (7002, 702, 'SKT', 'both', true, 'APPROVED'),
                    (8002, 802, 'SKT', 'both', true, 'APPROVED'),
                    (9002, 902, 'SKT', 'both', true, 'PENDING');
                """);
    }

    private static void seedViews(long partnerId, LocalDate... days) {
        int sequence = 1;
        for (LocalDate day : days) {
            String sessionHash = String.format("%064x", partnerId * 100 + sequence++);
            jdbc.getJdbcTemplate().update("""
                    INSERT INTO map_partner_view_daily (partner_id, anonymous_session_hash, view_day)
                    VALUES (?, ?, ?)
                    """, partnerId, sessionHash, Date.valueOf(day));
        }
    }

    private static MapSqlParameterSource popularityParams() {
        return new MapSqlParameterSource()
                .addValue("mainCategory", null, Types.VARCHAR)
                .addValue("category", null, Types.VARCHAR)
                .addValue("filter", null, Types.VARCHAR)
                .addValue("keyword", null, Types.VARCHAR)
                .addValue("partnerBrands", List.of(""))
                .addValue("brandOnly", false)
                .addValue("carrierFilterEnabled", false)
                .addValue("carriers", List.of("SKT", "KT", "LGU"))
                .addValue("gradeFilterEnabled", false)
                .addValue("grades", List.of(""))
                .addValue("sort", "POPULARITY")
                .addValue("viewFromDate", Date.valueOf(TODAY.minusDays(6)))
                .addValue("viewThroughDate", Date.valueOf(TODAY));
    }

    private static Query query(String methodName) {
        return Arrays.stream(PartnerRepository.class.getMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(method -> method.getAnnotation(Query.class))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
    }
}
