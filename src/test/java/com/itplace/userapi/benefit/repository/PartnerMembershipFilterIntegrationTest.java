package com.itplace.userapi.benefit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.partner.repository.PartnerRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PartnerMembershipFilterIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static HikariDataSource source;
    static NamedParameterJdbcTemplate jdbc;
    static Query query;

    @BeforeAll
    static void connect() {
        source = new HikariDataSource();
        source.setJdbcUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        var template = new JdbcTemplate(source);
        jdbc = new NamedParameterJdbcTemplate(template);
        template.execute("""
                CREATE TABLE partner (partnerId bigint PRIMARY KEY, partnerName text, category text);
                CREATE TABLE benefit (benefitId bigint PRIMARY KEY, partnerId bigint, mainCategory text, active boolean);
                CREATE TABLE benefitCarrierPolicy (benefitCarrierPolicyId bigint PRIMARY KEY,
                    benefitId bigint, carrier text, usageType text, active boolean);
                CREATE TABLE carrierTierBenefit (benefitCarrierPolicyId bigint, grade text);
                CREATE TABLE favorite (benefitId bigint);
                INSERT INTO partner VALUES (1,'A cafe','카페'),(2,'B cafe','카페'),(3,'C shop','쇼핑'),
                    (4,'D vipkok','카페'),(5,'E inactive','카페'),(6,'F inactive policy','카페'),
                    (7,'G unknown grade','카페'),(8,'H legacy','카페');
                INSERT INTO benefit VALUES (1,1,'BASIC',true),(2,2,'BASIC',true),(3,3,'BASIC',true),
                    (4,4,'VIP_COCK',true),(5,5,'BASIC',false),(6,6,'BASIC',true),
                    (7,7,'BASIC',true),(8,8,'BASIC',null);
                INSERT INTO benefitCarrierPolicy VALUES (11,1,'SKT','both',true),(12,1,'KT','online',true),
                    (21,2,'SKT','offline',true),(31,3,'KT','online',true),(41,4,'LGU','both',true),
                    (51,5,'SKT','both',true),(61,6,'SKT','both',false),
                    (71,7,'SKT','both',true),(81,8,'SKT','offline',null);
                INSERT INTO carrierTierBenefit VALUES (11,'SKT_GOLD'),(11,'SKT_GOLD'),(11,'SKT_SILVER'),
                    (12,'KT_VIP'),(21,'SKT_VIP'),(31,'KT_VIP'),(41,'VIP콕'),
                    (51,'SKT_GOLD'),(61,'SKT_GOLD'),(81,'SKT_GOLD');
                """);
        query = Arrays.stream(PartnerRepository.class.getMethods())
                .filter(method -> method.getName().equals("findBenefitPartners"))
                .findFirst().orElseThrow().getAnnotation(Query.class);
    }

    @AfterAll
    static void close() {
        if (source != null) source.close();
    }

    @Test
    void gradeFilterUsesTheSameActivePolicyAndPreservesCountsAndPagination() {
        var params = params("SKT", List.of("SKT_GOLD"));
        assertRows(params, 1L, 8L);
        assertThat(jdbc.query(query.value() + " LIMIT 1 OFFSET 1", params, (rs, row) -> rs.getLong("partnerId")))
                .containsExactly(8L);
        params.addValue("filter", "ONLINE", Types.VARCHAR);
        assertRows(params, 1L);
        params.addValue("category", "쇼핑", Types.VARCHAR);
        assertRows(params);

        assertRows(params("KT", List.of("KT_VIP")), 1L, 3L);
        assertRows(params("LGU", List.of("VIP", "VIP콕")), 4L);
        assertRows(params("LGU", List.of("BASIC")));
        assertRows(params("SKT", List.of("SKT_VIP")), 2L);
    }

    @Test
    void requestsWithoutGradeKeepExistingCarrierAndCategoryBehavior() {
        var params = params("SKT", List.of(""));
        params.addValue("gradeFilterEnabled", false);
        assertRows(params, 1L, 2L, 7L, 8L);
        params.addValue("carrierFilterEnabled", false);
        assertRows(params, 1L, 2L, 3L, 4L, 7L, 8L);
        params.addValue("mainCategory", "BASIC", Types.VARCHAR);
        params.addValue("keyword", "shop", Types.VARCHAR);
        assertRows(params, 3L);
    }

    private static void assertRows(MapSqlParameterSource params, Long... ids) {
        assertThat(jdbc.query(query.value(), params, (rs, row) -> rs.getLong("partnerId")))
                .containsExactly(ids);
        assertThat(jdbc.queryForObject(query.countQuery(), params, Long.class)).isEqualTo((long) ids.length);
    }

    private static MapSqlParameterSource params(String carrier, List<String> grades) {
        return new MapSqlParameterSource()
                .addValue("mainCategory", null, Types.VARCHAR).addValue("category", null, Types.VARCHAR)
                .addValue("filter", null, Types.VARCHAR).addValue("keyword", null, Types.VARCHAR)
                .addValue("sort", "NAME_ASC", Types.VARCHAR).addValue("carrierFilterEnabled", true)
                .addValue("carriers", List.of(carrier)).addValue("gradeFilterEnabled", true)
                .addValue("grades", grades);
    }
}
