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
                CREATE TABLE partner_category (code text PRIMARY KEY, label text);
                CREATE TABLE partner_category_alias (carrier_code text, source_label text, category_code text);
                CREATE TABLE partnerAlias (partnerAliasId bigint PRIMARY KEY, partnerId bigint,
                    aliasName text, normalizedAlias text, source text);
                INSERT INTO partner_category VALUES ('문화/여가','문화·여가'),('생활/편의','생활·편의');
                INSERT INTO partner_category_alias VALUES
                    ('SKT','영화','문화/여가'),('SKT','편의점','생활/편의');
                CREATE TABLE benefit (benefitId bigint PRIMARY KEY, partnerId bigint, benefitName text,
                    mainCategory text, active boolean);
                CREATE TABLE benefitCarrierPolicy (benefitCarrierPolicyId bigint PRIMARY KEY,
                    benefitId bigint, carrier text, usageType text, active boolean, conditions jsonb,
                    description text, manual text, sourceCategory text);
                CREATE TABLE carrierTierBenefit (benefitCarrierPolicyId bigint, grade text, context text, offerKey text);
                CREATE TABLE membership_grade_alias(carrier_code text, source_label text, grade_code text);
                CREATE TABLE favorite (benefitId bigint);
                INSERT INTO partner VALUES (1,'A cafe','카페'),(2,'B cafe','카페'),(3,'C shop','쇼핑'),
                    (4,'D vipkok','카페'),(5,'E inactive','카페'),(6,'F inactive policy','카페'),
                    (7,'G unknown grade','카페'),(8,'H legacy','카페'),(9,'I split offers','카페'),(10,'J unresolved','카페');
                INSERT INTO benefit VALUES (1,1,null,'BASIC',true),(2,2,null,'BASIC',true),(3,3,null,'BASIC',true),
                    (4,4,null,'VIP_COCK',true),(5,5,null,'BASIC',false),(6,6,null,'BASIC',true),
                    (7,7,null,'BASIC',true),(8,8,null,'BASIC',null),(9,9,null,'BASIC',true),(10,10,null,'BASIC',true);
                INSERT INTO benefitCarrierPolicy
                    (benefitCarrierPolicyId,benefitId,carrier,usageType,active,conditions)
                VALUES (11,1,'SKT','both',true,null),(12,1,'KT','online',true,null),
                    (21,2,'SKT','offline',true,null),(31,3,'KT','online',true,null),(41,4,'LGU','both',true,null),
                    (51,5,'SKT','both',true,null),(61,6,'SKT','both',false,null),
                    (71,7,'SKT','both',true,null),(81,8,'SKT','offline',null,null),
                    (91,9,'SKT','both',true,'{"offerResolution":"SCOPED","offers":[{"usageType":"ONLINE","gradeCodes":["SKT_VIP"]},{"usageType":"OFFLINE","gradeCodes":["SKT_GOLD"]}]}'),
                    (101,10,'SKT','both',true,'{"offerResolution":"UNRESOLVED","offers":[]}');
                INSERT INTO carrierTierBenefit (benefitCarrierPolicyId,grade)
                VALUES (11,'SKT_GOLD'),(11,'SKT_GOLD'),(11,'SKT_SILVER'),
                    (12,'KT_VIP'),(21,'SKT_VIP'),(31,'KT_VIP'),(41,'VIP콕'),(101,'SKT_GOLD'),
                    (51,'SKT_GOLD'),(61,'SKT_GOLD'),(81,'SKT_GOLD');
                INSERT INTO partner VALUES (11,'메가박스','문화/여가'),(12,'문화센터','문화/여가'),
                    (13,'GS25','생활/편의'),(14,'무관한 매장','생활/편의');
                INSERT INTO benefit VALUES (11,11,'영화 할인','BASIC',true),
                    (12,12,'전시 할인','BASIC',true),(13,13,'일반 할인','BASIC',true),
                    (14,14,'일반 할인','BASIC',true);
                INSERT INTO benefitCarrierPolicy
                    (benefitCarrierPolicyId,benefitId,carrier,usageType,active,conditions,description,manual,sourceCategory)
                VALUES (111,11,'SKT','offline',true,null,'영화 제휴','','영화'),
                    (112,12,'SKT','offline',true,null,'전시 제휴','영화관 정보가 매뉴얼에만 있음','공연'),
                    (113,13,'SKT','offline',true,null,'생활 혜택','','생활/편의'),
                    (114,14,'SKT','offline',true,null,'생활 혜택','','생활/편의');
                INSERT INTO carrierTierBenefit (benefitCarrierPolicyId,grade,context)
                VALUES (114,'SKT_GOLD','검색어 전용 문맥');
                INSERT INTO partner VALUES (15,'브랜드 이름','카페');
                INSERT INTO benefit VALUES (15,15,'일반 쿠폰','BASIC',true);
                INSERT INTO benefitCarrierPolicy
                    (benefitCarrierPolicyId,benefitId,carrier,usageType,active,conditions,description,manual)
                VALUES (115,15,'SKT','offline',true,null,'일반 혜택','');
                INSERT INTO partnerAlias VALUES (1,15,'검색 별칭','검색별칭','MANUAL');
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
        assertRows(params, 1L, 8L, 9L, 10L, 14L);
        assertThat(jdbc.query(query.value() + " LIMIT 1 OFFSET 1", params, (rs, row) -> rs.getLong("partnerId")))
                .containsExactly(8L);
        params.addValue("filter", "ONLINE", Types.VARCHAR);
        assertRows(params, 1L);
        params.addValue("category", "쇼핑", Types.VARCHAR);
        assertRows(params);

        assertRows(params("KT", List.of("KT_VIP")), 1L, 3L);
        assertRows(params("LGU", List.of("VIP", "VIP콕")), 4L);
        assertRows(params("LGU", List.of("BASIC")));
        assertRows(params("SKT", List.of("SKT_VIP")), 2L, 9L);
        var unresolved = params("SKT", List.of("SKT_GOLD"));
        unresolved.addValue("filter", "OFFLINE", Types.VARCHAR);
        assertRows(unresolved, 1L, 8L, 9L, 14L);
        var splitOffers = params("SKT", List.of("SKT_VIP"));
        splitOffers.addValue("filter", "OFFLINE", Types.VARCHAR);
        assertRows(splitOffers, 2L);
        splitOffers.addValue("grades", List.of("SKT_GOLD"));
        assertRows(splitOffers, 1L, 8L, 9L, 14L);
    }

    @Test
    void requestsWithoutGradeKeepExistingCarrierAndCategoryBehavior() {
        var params = params("SKT", List.of(""));
        params.addValue("gradeFilterEnabled", false);
        assertRows(params, 1L, 2L, 7L, 13L, 8L, 9L, 10L, 11L, 14L, 12L, 15L);
        params.addValue("carrierFilterEnabled", false);
        assertRows(params, 1L, 2L, 3L, 4L, 7L, 13L, 8L, 9L, 10L, 11L, 14L, 12L, 15L);
        params.addValue("mainCategory", "BASIC", Types.VARCHAR);
        params.addValue("keyword", "shop", Types.VARCHAR);
        assertRows(params, 3L);
    }

    @Test
    void keywordSearchUsesCategoryAliasesAndRelevantOfferTextWithoutBroadMovieOrManualMatches() {
        var params = params("SKT", List.of(""));
        params.addValue("gradeFilterEnabled", false).addValue("carrierFilterEnabled", false);
        params.addValue("keyword", "영화관");
        assertRows(params, 11L);
        params.addValue("keyword", "편의점");
        params.addValue("partnerBrands", List.of("gs25", "cu", "세븐일레븐", "이마트24"));
        assertRows(params, 13L);
        params.addValue("partnerBrands", List.of(""));
        params.addValue("keyword", "검색어 전용");
        assertRows(params, 14L);
        params.addValue("keyword", "영화관 정보");
        assertRows(params);
        params.addValue("keyword", "검색 별칭");
        assertRows(params, 15L);

        params.addValue("keyword", "편의점");
        params.addValue("partnerBrands", List.of("gs25", "cu", "세븐일레븐", "이마트24"));
        assertThat(jdbc.query(query.value() + " LIMIT 1 OFFSET 1", params,
                (rs, row) -> rs.getLong("partnerId"))).isEmpty();
        assertThat(jdbc.queryForObject(query.countQuery(), params, Long.class)).isEqualTo(1L);

        jdbc.getJdbcTemplate().update("INSERT INTO partner VALUES (16,'Offer scope test','카페')");
        jdbc.getJdbcTemplate().update("INSERT INTO benefit VALUES (16,16,'일반 쿠폰','BASIC',true)");
        jdbc.getJdbcTemplate().update("""
                INSERT INTO benefitCarrierPolicy
                    (benefitCarrierPolicyId,benefitId,carrier,usageType,active,conditions,description,manual,sourceCategory)
                VALUES (116,16,'SKT','both',true,
                    '{"offerResolution":"SCOPED","offers":[{"key":"vip-offline","usageType":"OFFLINE","gradeCodes":["SKT_VIP"]},{"key":"basic-online","usageType":"ONLINE","gradeCodes":["SKT_GOLD"]}]}',
                    '등급 범위 테스트','','영화관')
                """);
        jdbc.getJdbcTemplate().update("INSERT INTO carrierTierBenefit (benefitCarrierPolicyId,grade,context,offerKey) VALUES " +
                "(116,'SKT_VIP','VIP 전용 문맥','vip-offline'),(116,'SKT_GOLD','기본 온라인 문맥','basic-online')");
        try {
            var scoped = params("SKT", List.of("SKT_GOLD"));
            scoped.addValue("filter", "ONLINE", Types.VARCHAR).addValue("keyword", "VIP 전용 문맥");
            assertRows(scoped);
            scoped.addValue("keyword", "기본 온라인 문맥");
            assertRows(scoped, 16L);
            scoped.addValue("filter", null, Types.VARCHAR).addValue("keyword", "VIP 전용 문맥");
            assertRows(scoped);
            scoped.addValue("keyword", "기본 온라인 문맥");
            assertRows(scoped, 16L);
        } finally {
            jdbc.getJdbcTemplate().update("DELETE FROM carrierTierBenefit WHERE benefitCarrierPolicyId = 116");
            jdbc.getJdbcTemplate().update("DELETE FROM benefitCarrierPolicy WHERE benefitCarrierPolicyId = 116");
            jdbc.getJdbcTemplate().update("DELETE FROM benefit WHERE benefitId = 16");
            jdbc.getJdbcTemplate().update("DELETE FROM partner WHERE partnerId = 16");
        }
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
                .addValue("partnerBrands", List.of(""))
                .addValue("sort", "NAME_ASC", Types.VARCHAR).addValue("carrierFilterEnabled", true)
                .addValue("carriers", List.of(carrier)).addValue("gradeFilterEnabled", true)
                .addValue("grades", grades);
    }
}
