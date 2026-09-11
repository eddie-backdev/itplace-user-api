package com.itplace.userapi.map.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class StorePreviewQueryIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));
    JdbcTemplate jdbc;
    NamedParameterJdbcTemplate named;
    String sql;

    @BeforeEach
    void prepare() throws Exception {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        sql = StoreRepository.class.getMethod("findStorePreviewsInView", double.class, double.class,
                double.class, double.class, double.class, double.class, String.class, int.class)
                .getAnnotation(Query.class).value();
        jdbc.execute("""
                DROP TABLE IF EXISTS store, partner, benefit, benefitCarrierPolicy;
                CREATE TABLE partner (partnerId bigint PRIMARY KEY, partnerName text, category text, image text);
                CREATE TABLE benefit (benefitId bigint PRIMARY KEY, partnerId bigint, active boolean);
                CREATE TABLE benefitCarrierPolicy (benefitId bigint, active boolean, usageType text);
                CREATE TABLE store (storeId bigint PRIMARY KEY, partnerId bigint, storeName text, business text,
                    location geography(Point,4326), latitude float8, longitude float8, active boolean,
                    address text, roadName text, roadAddress text, postCode text, hasCoupon boolean);
                CREATE INDEX ON store USING gist(location);
                INSERT INTO partner SELECT n, CASE WHEN n=2 THEN '다락' ELSE '제휴사'||n END,
                    CASE WHEN n=2 THEN '보관' ELSE '생활' END, NULL FROM generate_series(1,9) n;
                INSERT INTO benefit SELECT n,n,CASE WHEN n=4 THEN false WHEN n=7 THEN NULL ELSE true END
                    FROM generate_series(1,9) n WHERE n<>6;
                INSERT INTO benefit VALUES (20,1,true),(21,NULL,true);
                INSERT INTO benefitCarrierPolicy SELECT benefitId,
                    CASE WHEN benefitId=5 THEN false WHEN benefitId=7 THEN NULL ELSE true END,
                    CASE WHEN benefitId=3 THEN 'online' WHEN benefitId=8 THEN NULL
                         WHEN benefitId=9 THEN 'both' ELSE 'offline' END FROM benefit;
                INSERT INTO benefitCarrierPolicy VALUES (1,true,'both');
                INSERT INTO store (storeId,partnerId,storeName,business,latitude,longitude,active)
                    VALUES (1,1,'첫 매장','소매',37.5,127,true),(2,1,'동일 거리','소매',37.5,127,true),
                    (3,2,'다락 보관','보관',37.5005,127,true),(4,2,'다락 요양','요양',37.5,127,true),
                    (5,3,'온라인','소매',37.5,127,true),(6,4,'혜택 중지','소매',37.5,127,true),
                    (7,5,'정책 중지','소매',37.5,127,true),(8,6,'혜택 없음','소매',37.5,127,true),
                    (9,7,'기존 활성값 NULL','소매',37.5,127,true),(10,8,'사용처 NULL','소매',37.5,127,true),
                    (11,9,'온라인 오프라인','소매',37.5,127,true),(12,1,'매장 활성 NULL','소매',37.5,127,NULL),
                    (13,1,'매장 중지','소매',37.5,127,false),(14,1,'화면 밖','소매',38,128,true),
                    (15,1,'좌표 없음','소매',NULL,NULL,true),(16,1,'경계','소매',37.51,127.01,true);
                UPDATE store SET location=ST_SetSRID(ST_MakePoint(longitude,latitude),4326)::geography;
                """);
    }

    @Test
    void keepsEligibilityBoundsCategoryOrderingAndLimitWithoutDuplicateStores() {
        assertThat(ids(null,300)).containsExactly(1L,2L,9L,11L,3L,16L);
        assertThat(ids("생활",300)).containsExactly(1L,2L,9L,11L,16L);
        assertThat(ids("보관",300)).containsExactly(3L);
        assertThat(ids("없는 분류",300)).isEmpty();
        assertThat(ids(null,2)).containsExactly(1L,2L);
        jdbc.update("UPDATE benefitCarrierPolicy SET active=false");
        assertThat(ids(null,300)).isEmpty();
    }

    @Test
    void computesEligiblePartnersOnceForHundredsOfStores() throws Exception {
        jdbc.execute("""
                INSERT INTO store (storeId,partnerId,storeName,business,latitude,longitude,location,active)
                    SELECT n,1,'매장'||n,'소매',37.5,127,ST_SetSRID(ST_MakePoint(127,37.5),4326)::geography,true
                    FROM generate_series(1000,1399) n;
                ANALYZE store;
                ANALYZE partner;
                ANALYZE benefit;
                ANALYZE benefitCarrierPolicy;
                """);
        JsonNode plan = new ObjectMapper().readTree(named.queryForObject(
                "EXPLAIN (ANALYZE, FORMAT JSON) " + sql, params(null,300), String.class));
        JsonNode policyScan = findPolicyScan(plan.get(0).get("Plan"));
        assertThat(policyScan).isNotNull();
        assertThat(policyScan.path("Actual Loops").asInt()).isEqualTo(1);
        assertThat(ids(null,300)).hasSize(300).doesNotHaveDuplicates();
    }

    private List<Long> ids(String category, int limit) {
        return named.query(sql, params(category,limit), (row,index) -> row.getLong("storeId"));
    }

    private MapSqlParameterSource params(String category, int limit) {
        return new MapSqlParameterSource().addValue("minLat",37.49).addValue("maxLat",37.51)
                .addValue("minLng",126.99).addValue("maxLng",127.01)
                .addValue("centerLat",37.5).addValue("centerLng",127.0)
                .addValue("category",category,Types.VARCHAR).addValue("limit",limit);
    }

    private JsonNode findPolicyScan(JsonNode node) {
        if ("benefitcarrierpolicy".equals(node.path("Relation Name").asText())) return node;
        for (JsonNode child : node.path("Plans")) {
            JsonNode found = findPolicyScan(child);
            if (found != null) return found;
        }
        return null;
    }
}
