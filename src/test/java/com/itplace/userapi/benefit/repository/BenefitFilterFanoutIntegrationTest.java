package com.itplace.userapi.benefit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Native-query regression against independent SQL captured before favorite fanout removal. */
@Testcontainers(disabledWithoutDocker = true)
class BenefitFilterFanoutIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));
    static HikariDataSource source;
    static JdbcTemplate jdbc;
    static NamedParameterJdbcTemplate named;
    static String oldData;
    static String oldCount;
    static Query current;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void connect() throws Exception {
        source = new HikariDataSource();
        source.setJdbcUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setMaximumPoolSize(1);
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(15);
        named = new NamedParameterJdbcTemplate(jdbc);
        jdbc.execute("""
                CREATE TABLE partner (partnerId bigint PRIMARY KEY, partnerName text, category text);
                CREATE TABLE benefit (benefitId bigint PRIMARY KEY, partnerId bigint REFERENCES partner,
                    benefitName text, mainCategory text, active boolean);
                CREATE TABLE benefitCarrierPolicy (benefitCarrierPolicyId bigint PRIMARY KEY,
                    benefitId bigint REFERENCES benefit, carrier text, usageType text,
                    description text, manual text, active boolean);
                CREATE TABLE carrierTierBenefit (tierId bigint PRIMARY KEY,
                    benefitCarrierPolicyId bigint REFERENCES benefitCarrierPolicy, context text);
                CREATE TABLE favorite (benefitId bigint REFERENCES benefit, userId bigint,
                    PRIMARY KEY (benefitId, userId));
                CREATE INDEX idx_policy_benefit ON benefitCarrierPolicy (benefitId);
                CREATE INDEX idx_tier_policy ON carrierTierBenefit (benefitCarrierPolicyId);
                SET jit = off;
                SET work_mem = '4MB';
                """);
        current = Arrays.stream(BenefitRepository.class.getMethods())
                .filter(method -> method.getName().equals("findFilteredBenefits")).findFirst().orElseThrow()
                .getAnnotation(Query.class);
        oldData = resource("filter-before-fanout-data.sql");
        oldCount = resource("filter-before-fanout-count.sql");
    }

    @AfterAll
    static void close() { if (source != null) source.close(); }

    @BeforeEach
    void smallFixture() {
        jdbc.execute("TRUNCATE favorite, carrierTierBenefit, benefitCarrierPolicy, benefit, partner CASCADE");
        jdbc.execute("""
                INSERT INTO partner VALUES (1,'카페 파트너','카페'), (2,'푸드 파트너','푸드'),
                    (3,'ABC_shop','쇼핑'), (4,NULL,NULL);
                INSERT INTO benefit VALUES
                    (1,1,'같은 이름','FOOD',true), (2,1,'같은 이름','FOOD',true),
                    (3,2,'커피 쿠폰','FOOD',true), (4,3,'ABC 할인','SHOPPING',true),
                    (5,2,'비활성','FOOD',false), (6,4,'정책 없음',NULL,true),
                    (7,1,'Legacy null active','FOOD',NULL), (8,NULL,'파트너 없음',NULL,true),
                    (9,1,'Aa','FOOD',true), (10,1,'aa','FOOD',true), (11,1,NULL,'FOOD',true),
                    (12,1,'inactive policy only','FOOD',true);
                INSERT INTO benefitCarrierPolicy VALUES
                    (11,1,'SKT','offline','설명','안내',true), (12,1,'KT','both','other','manual',true),
                    (13,1,'LGU','online','다른 내용',NULL,true),
                    (21,2,'SKT','offline','설명','manual',true), (22,2,'KT','online','online only',NULL,true),
                    (23,2,'KT','both','비활성','manual',false),
                    (31,3,'KT','online','선물','manual',true), (41,4,'LGU','both','할인','guide',true),
                    (51,5,'SKT','offline','설명','안내',true), (71,7,'KT','both','legacy',NULL,NULL),
                    (81,8,'SKT','both','orphan',NULL,true), (91,9,'SKT','offline',NULL,NULL,true),
                    (101,10,'KT','offline',NULL,NULL,true), (111,11,'KT','online',NULL,NULL,true),
                    (121,12,'SKT','both','설명','안내',false);
                INSERT INTO carrierTierBenefit VALUES (1,11,'등급 VIP'), (2,11,'등급 GOLD'),
                    (3,12,'등급 VIP'), (4,31,'coupon'), (5,23,'비활성 등급');
                INSERT INTO favorite VALUES (1,1),(1,2),(1,3),(2,1),(2,2),(2,3),
                    (3,1),(3,2),(3,3),(3,4),(3,5),(5,1),(7,1),(9,1),(10,1);
                ANALYZE;
                """);
    }

    @Test
    void dataCountSortingAndPagesMatchAcrossFiltersAndHistoricalNulls() throws Exception {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO favorite VALUES (1,1)"))
                .isInstanceOf(DataIntegrityViolationException.class);
        List<MapSqlParameterSource> cases = new ArrayList<>();
        for (String keyword : Arrays.asList(null, "", "카페", "설명", "등급", "manual", "없는검색어", "%", "_")) {
            cases.add(params(null, null, null, keyword, false, List.of("SKT")));
        }
        Random random = new Random(20260911);
        List<String> mainCategories = Arrays.asList(null, "FOOD", "SHOPPING", "missing");
        List<String> categories = Arrays.asList(null, "카페", "푸드", "쇼핑", "missing");
        List<String> filters = Arrays.asList(null, "ONLINE", "OFFLINE");
        List<String> keywords = Arrays.asList(null, "", "같은", "카페", "설명", "등급", "manual", "Aa", "없는검색어", "%", "_");
        List<List<String>> carriers = List.of(List.of("SKT"), List.of("KT"), List.of("LGU"),
                List.of("SKT", "KT", "LGU"), List.of("missing"));
        for (int i = 0; i < 96; i++) {
            cases.add(params(mainCategories.get(random.nextInt(mainCategories.size())),
                    categories.get(random.nextInt(categories.size())), filters.get(random.nextInt(filters.size())),
                    keywords.get(random.nextInt(keywords.size())), random.nextBoolean(),
                    carriers.get(random.nextInt(carriers.size()))));
        }
        int checked = 0;
        for (MapSqlParameterSource parameters : cases) {
            for (String sort : List.of("POPULARITY", "NAME_ASC", "NAME_DESC", "LATEST")) {
                parameters.addValue("sort", sort, Types.VARCHAR);
                List<Map<String, Object>> before = named.queryForList(oldData, parameters);
                List<Map<String, Object>> after = named.queryForList(current.value(), parameters);
                assertThat(after).as("case=%s sort=%s", checked, sort).isEqualTo(before);
                Long beforeCount = named.queryForObject(oldCount, parameters, Long.class);
                Long afterCount = named.queryForObject(current.countQuery(), parameters, Long.class);
                assertThat(afterCount).isEqualTo(beforeCount).isEqualTo((long) after.size());
                parameters.addValue("limit", new int[]{1, 3, 7}[checked % 3]);
                parameters.addValue("offset", checked % 4);
                assertThat(named.queryForList(current.value() + " LIMIT :limit OFFSET :offset", parameters))
                        .isEqualTo(named.queryForList(oldData + " LIMIT :limit OFFSET :offset", parameters));
                checked++;
            }
        }
        var unfiltered = params(null, null, null, null, false, List.of("SKT")).addValue("sort", "POPULARITY");
        assertThat(named.queryForList(current.value(), unfiltered)).extracting(row -> row.get("benefitid"))
                .containsExactly(3L,1L,2L,7L,9L,10L,4L,6L,8L,11L);
        evidence("equivalence.json", Map.of("database", jdbc.queryForObject("SELECT version()", String.class),
                "seed", 20260911, "filterCases", cases.size(), "sortModes", 4, "checkedCases", checked,
                "dataCountPageEquivalent", true, "ordering", "exact, including benefitId ASC tie break",
                "fixture", Map.of("benefits", 12, "policies", 15, "favorites", 15)));
    }

    @Test
    void favoriteGrowthDoesNotMultiplyPolicyRowsBeforeAggregation() throws Exception {
        jdbc.execute("TRUNCATE favorite, carrierTierBenefit, benefitCarrierPolicy, benefit, partner CASCADE");
        jdbc.execute("""
                INSERT INTO partner VALUES (1,'성장 fixture','카페');
                INSERT INTO benefit SELECT i,1,'benefit '||(i%20),'FOOD',true FROM generate_series(1,400) i;
                INSERT INTO benefitCarrierPolicy
                    SELECT b.benefitId*10+p,b.benefitId,
                           CASE p WHEN 1 THEN 'SKT' WHEN 2 THEN 'KT' ELSE 'LGU' END,
                           'both','description','manual',true
                    FROM benefit b CROSS JOIN generate_series(1,3) p;
                INSERT INTO favorite SELECT b.benefitId,u FROM benefit b CROSS JOIN generate_series(1,250) u;
                ANALYZE;
                """);
        var parameters = params(null, null, null, null, false, List.of("SKT"))
                .addValue("sort", "POPULARITY", Types.VARCHAR).addValue("limit", 20);
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("before-data", oldData + " LIMIT :limit");
        queries.put("after-data", current.value() + " LIMIT :limit");
        queries.put("before-count", oldCount);
        queries.put("after-count", current.countQuery());
        for (String sql : queries.values()) named.queryForList(sql, parameters);
        assertThat(named.queryForList(current.value(), parameters)).isEqualTo(named.queryForList(oldData, parameters));
        assertThat(named.queryForObject(current.countQuery(), parameters, Long.class)).isEqualTo(400);
        Map<String, List<Double>> times = new LinkedHashMap<>();
        Map<String, Long> cardinalities = new LinkedHashMap<>();
        for (int round = 0; round < 5; round++) {
            List<String> order = round % 2 == 0 ? List.copyOf(queries.keySet())
                    : List.of("after-data", "before-data", "after-count", "before-count");
            for (String name : order) {
                String json = named.queryForObject("EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, FORMAT JSON) "
                        + queries.get(name), parameters, String.class);
                JsonNode plan = MAPPER.readTree(json).get(0);
                times.computeIfAbsent(name, ignored -> new ArrayList<>()).add(plan.path("Execution Time").asDouble());
                cardinalities.put(name, largestJoinOutput(plan.path("Plan")));
                evidence(name + "-plan-" + (round + 1) + ".json", MAPPER.readTree(json));
            }
        }
        assertThat(cardinalities.get("before-data")).isEqualTo(300_000);
        assertThat(cardinalities.get("after-data")).isEqualTo(1_200);
        assertThat(cardinalities.get("before-count")).isEqualTo(300_000);
        assertThat(cardinalities.get("after-count")).isEqualTo(1_200);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("database", jdbc.queryForObject("SELECT version()", String.class));
        summary.put("fixture", Map.of("benefits",400,"policiesPerBenefit",3,"favoritesPerBenefit",250,
                "policies",1200,"favorites",100000));
        summary.put("settings", Map.of("jit", "off", "work_mem", "4MB", "dataLimit",20));
        summary.put("largestJoinOutputRows", cardinalities);
        summary.put("executionMilliseconds", times);
        Map<String, Double> medians = new LinkedHashMap<>();
        times.forEach((name, values) -> medians.put(name, values.stream().sorted().toList().get(2)));
        summary.put("medianExecutionMilliseconds", medians);
        summary.put("limitations", "Synthetic single-connection PostgreSQL fixture; not production latency, TPS, HTTP, or JPA hydration evidence.");
        evidence("fanout-summary.json", summary);
    }

    private static long largestJoinOutput(JsonNode node) {
        String type = node.path("Node Type").asText();
        long rows = type.contains("Join") || type.equals("Nested Loop")
                ? node.path("Actual Rows").asLong() * node.path("Actual Loops").asLong() : 0;
        for (JsonNode child : node.path("Plans")) rows = Math.max(rows, largestJoinOutput(child));
        return rows;
    }

    private static MapSqlParameterSource params(String mainCategory, String category, String filter, String keyword,
                                                boolean carrierFilterEnabled, List<String> carriers) {
        return new MapSqlParameterSource().addValue("mainCategory", mainCategory, Types.VARCHAR)
                .addValue("category", category, Types.VARCHAR).addValue("filter", filter, Types.VARCHAR)
                .addValue("keyword", keyword, Types.VARCHAR).addValue("carrierFilterEnabled", carrierFilterEnabled)
                .addValue("carriers", carriers, Types.VARCHAR);
    }

    private static String resource(String name) throws Exception {
        try (var stream = Objects.requireNonNull(BenefitFilterFanoutIntegrationTest.class
                .getResourceAsStream("/benefit/" + name))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void evidence(String name, Object value) throws Exception {
        String directory = System.getProperty("benefit.fanout.evidence");
        if (directory == null) return;
        Path path = Path.of(directory);
        Files.createDirectories(path);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.resolve(name).toFile(), value);
    }
}
