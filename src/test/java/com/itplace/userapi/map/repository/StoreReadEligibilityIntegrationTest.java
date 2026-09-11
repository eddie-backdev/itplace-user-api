package com.itplace.userapi.map.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.map.entity.Store;
import com.itplace.userapi.map.service.StorePartnerBusinessPolicy;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class StoreReadEligibilityIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));
    private static final Pattern NON_SEARCH = Pattern.compile("[^가-힣a-z0-9]+");
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    NamedParameterJdbcTemplate named;

    @BeforeEach
    void prepare() throws Exception {
        source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(source);
        jdbc.execute("""
                DROP TABLE IF EXISTS store, partner, benefit, benefitCarrierPolicy;
                DROP FUNCTION IF EXISTS map_store_partner_matches(text,text,text), map_normalize_name(text);
                CREATE TABLE partner (partnerId bigint PRIMARY KEY, partnerName text, category text, image text);
                CREATE TABLE benefit (benefitId bigint PRIMARY KEY, partnerId bigint, active boolean);
                CREATE TABLE benefitCarrierPolicy (benefitCarrierPolicyId bigint PRIMARY KEY, benefitId bigint, active boolean, usageType text);
                CREATE TABLE store (storeId bigint PRIMARY KEY, partnerId bigint, storeName text, business text,
                    location geometry(Point,4326), latitude numeric(15,12), longitude numeric(15,12), active boolean,
                    address text, roadName text, roadAddress text, postCode text, hasCoupon boolean,
                    city text, town text, legalDong text, sourceProvider text, sourcePlaceId text,
                    lastSeenAt timestamp, lastSeenRunId text, healthyMissCount int, inactivatedAt timestamp);
                CREATE INDEX ON store USING gist(location);
                INSERT INTO partner VALUES (1,'GS25','편의점',NULL),(2,'다락','생활',NULL),(3,'Alpha','카페',NULL);
                INSERT INTO benefit SELECT n,n,true FROM generate_series(1,3) n;
                INSERT INTO benefitCarrierPolicy SELECT n,n,true,'offline' FROM generate_series(1,3) n;
                """);
        try (var input = getClass().getResourceAsStream("/db/migration/V20260911_0002__store_map_read_normalization.sql")) {
            jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void normalizationMatchesJavaRootForAllDefinedUnicodeCodePointsAndNull() {
        StringBuilder text = new StringBuilder();
        for (int codePoint = 1; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (Character.isDefined(codePoint) && !(codePoint >= 0xd800 && codePoint <= 0xdfff)) {
                text.appendCodePoint(codePoint);
            }
        }
        String value = text.toString();
        assertThat(jdbc.queryForObject("SELECT map_normalize_name(?)",String.class,value)).isEqualTo(normalize(value));
        assertThat(jdbc.queryForObject("SELECT map_normalize_name(NULL)",String.class)).isEmpty();
    }

    @Test
    void aliasAndBusinessPredicatesMatchExistingJavaRuleIncludingPunctuationCaseAndNull() {
        List<String[]> cases = List.of(
                new String[]{"[G.S-25] 강남점","소매","GS25"},
                new String[]{"지에스 25 역삼점",null,"g.s-25"},
                new String[]{"GS25 강남점",null,"지에스25"},
                new String[]{"씨 유-강남",null,"C.U"},
                new String[]{"Cu 강남점",null,"씨유"},
                new String[]{"7-ELEVEN 강남",null,"세븐일레븐"},
                new String[]{"세븐 일레븐 강남",null,"7eleven"},
                new String[]{"다락 미니창고","물품 보-관 업","다락"},
                new String[]{"미니 창고 다락 강남","저 장 공간","미니창고다락"},
                new String[]{"다락 요양원","요양","다락"},
                new String[]{"다락",null,"다락"},
                new String[]{"GS25",null,""},
                new String[]{"GS25",null,null},
                new String[]{null,null,"GS25"},
                new String[]{"!!!!!","저장","!!!!"},
                new String[]{"CU점","소매","GS25"},
                new String[]{"팬시랜드","놀이","서울랜드"},
                new String[]{"A.lPhA점","카페","alpha"},
                new String[]{"iK점",null,"İK"},
                new String[]{"가 나\t다 😀",null,"가나다"}
        );
        for (String[] fixture : cases) {
            Boolean actual = jdbc.queryForObject("SELECT map_store_partner_matches(map_normalize_name(?),map_normalize_name(?),map_normalize_name(?))",
                    Boolean.class, fixture[0], fixture[1], fixture[2]);
            assertThat(actual).as(Arrays.toString(fixture)).isEqualTo(legacyMatches(fixture[0],fixture[1],fixture[2]));
        }
    }

    @Test
    void generatedFieldsFollowStoreBusinessAndPartnerWritesWithoutSyncJob() {
        store(1,2,"다락 강남점","요양",37.5);
        assertThat(viewportIds(300)).isEmpty();
        jdbc.update("UPDATE store SET business='보-관 서비스' WHERE storeId=1");
        assertThat(viewportIds(300)).containsExactly(1L);
        jdbc.update("UPDATE store SET storeName='GS-25 강남점' WHERE storeId=1");
        assertThat(viewportIds(300)).isEmpty();
        jdbc.update("UPDATE partner SET partnerName='지에스25' WHERE partnerId=2");
        assertThat(viewportIds(300)).containsExactly(1L);
        assertThat(jdbc.queryForMap("SELECT mapNormalizedName,mapNormalizedBusiness FROM store WHERE storeId=1"))
                .containsEntry("mapnormalizedname","gs25강남점").containsEntry("mapnormalizedbusiness","보관서비스");
    }

    @Test
    void viewportFiltersInvalidNearestRowsBeforeLimitAndUsesNormalizedBusiness() {
        for (int i=1;i<=305;i++) store(i,1,"잘못 연결된 매장 "+i,"소매",37.5);
        for (int i=401;i<=705;i++) store(i,1,"지에스-25 정상 "+i,"소매",37.501);
        store(800,2,"다락 강남점","물품 보-관",37.5005);
        assertThat(viewportIds(300)).hasSize(300).startsWith(800L,401L).allMatch(id -> id >= 401L);
        assertThat(viewportIds(2)).containsExactly(800L,401L);
    }

    @Test
    void radiusCandidatesApplyEligibilityBeforeLimitButLegacyMobileCandidatesRemainUnchanged() {
        store(1,1,"무관한 매장","소매",37.5);
        store(2,1,"GS25 정상","소매",37.5005);
        store(3,2,"다락 요양","요양",37.5005);
        store(4,2,"다락 창고","저 장",37.5005);
        var parameters = parameters(null).addValue("radiusMeters",1000.0).addValue("limit",300);
        assertThat(ids("findStoreIdsInRadius",parameters)).containsExactlyInAnyOrder(1L,2L,3L,4L);
        assertThat(ids("findEligibleStoreIdsWithinRadius",parameters)).containsExactlyInAnyOrder(2L,4L);
        parameters.addValue("category","편의점").addValue("limit",1);
        assertThat(ids("findEligibleStoreIdsWithinRadius",parameters)).containsExactly(2L);
        parameters.addValue("category","없는 분류");
        assertThat(ids("findEligibleStoreIdsWithinRadius",parameters)).isEmpty();
    }

    @Test
    void keywordFiltersBeforeThirtyLimitAndPreservesExactPartialWildcardPriority() {
        for (int i=1;i<=35;i++) store(i,3,"다른 매장 "+i,"카페",37.5);
        store(40,3,"Alpha 지점","카페",37.501);
        store(41,3,"Alpha_beta","카페",37.502);
        store(42,3,"aLpHa","카페",37.503);
        var parameters = parameters(null).addValue("keyword","alpha");
        assertThat(ids("searchNearbyStoreIds",parameters)).hasSize(30).doesNotContain(40L,41L,42L);
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(40L,41L,42L);
        parameters.addValue("keyword","alpha%");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(40L,41L,42L);
        parameters.addValue("keyword","alpha_");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(40L,41L);
        parameters.addValue("keyword","alpha\\_");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(41L);
        parameters.addValue("category","편의점");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).isEmpty();
        // Exact partner-name hits stay ahead of a closer business-only hit.
        store(50,1,"GS25 카페","alpha",37.5001);
        parameters.addValue("category",null,Types.VARCHAR).addValue("keyword","alpha");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(40L,41L,42L,50L);
    }

    @Test
    void disjointKeywordBranchesKeepNullAliasPartnerOnlyAndWildcardMatchesWithoutDuplicates() {
        store(1,1,"GS25 서울",null,37.501);
        store(2,1,"지에스25 서울",null,37.502);
        store(3,1,null,null,37.503);
        store(4,1,"GS25_서초","소매",37.504);
        store(5,1,"GS25%서초","소매",37.505);
        store(6,1,"다른 가게","GS25",37.506);
        store(7,2,"다락 창고","보관",37.507);
        var parameters = parameters(null).addValue("keyword","gs25");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(1L,2L,4L,5L);
        parameters.addValue("keyword","gs25\\_");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(4L);
        parameters.addValue("keyword","gs25\\%");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(5L);
        parameters.addValue("keyword","%");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(1L,2L,4L,5L,7L);
        parameters.addValue("category","생활");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(7L);
        parameters.addValue("keyword","편의점").addValue("category",null,Types.VARCHAR);
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(1L,2L,4L,5L);
        store(20,1,"GS25 동순위 B",null,37.508);
        store(10,1,"GS25 동순위 A",null,37.508);
        parameters.addValue("keyword","동순위");
        assertThat(ids("searchEligibleNearbyStoreIds",parameters)).containsExactly(10L,20L);
    }

    @Test
    void finalHibernateJoinFetchRejectsStaleEsIdsAndLoadsPartnerWithoutLazyRead() {
        store(1,1,"GS25 정상","소매",37.5);
        store(2,1,"다른 매장","소매",37.5);
        store(3,2,"다락 요양","요양",37.5);
        store(4,2,"다락 창고","보 관",37.5);
        store(5,3,"Alpha 중지","카페",37.5);
        jdbc.update("UPDATE benefit SET active=false WHERE partnerId=3");
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("com.itplace.userapi");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none"));
        factory.afterPropertiesSet();
        try {
            List<Store> eligible;
            try (var manager = factory.getObject().createEntityManager()) {
                eligible = manager.createQuery(query("findEligibleByStoreIdInWithPartner"),Store.class)
                        .setParameter("storeIds",List.of(1L,2L,3L,4L,5L,999L)).getResultList();
                assertThat(manager.createQuery(query("findAllByStoreIdInWithPartner"),Store.class)
                        .setParameter("storeIds",List.of(1L,2L,3L,4L,5L)).getResultList())
                        .extracting(Store::getStoreId).containsExactlyInAnyOrder(1L,2L,3L,4L);
            }
            assertThat(eligible).extracting(Store::getStoreId).containsExactlyInAnyOrder(1L,4L);
            assertThat(eligible).allSatisfy(store -> assertThat(store.getPartner().getPartnerName()).isNotBlank());
        } finally {
            factory.destroy();
        }
    }

    private void store(long id, long partnerId, String name, String business, double latitude) {
        jdbc.update("INSERT INTO store (storeId,partnerId,storeName,business,latitude,longitude,location,active,hasCoupon,healthyMissCount) VALUES (?,?,?,?,?,127,ST_SetSRID(ST_MakePoint(127,?),4326),true,false,0)",
                id,partnerId,name,business,latitude,latitude);
    }

    private List<Long> viewportIds(int limit) {
        return ids("findStorePreviewsInView",parameters(null).addValue("limit",limit));
    }

    private MapSqlParameterSource parameters(String category) {
        return new MapSqlParameterSource().addValue("minLat",37.49).addValue("maxLat",37.51)
                .addValue("minLng",126.99).addValue("maxLng",127.01)
                .addValue("lat",37.5).addValue("lng",127.0)
                .addValue("centerLat",37.5).addValue("centerLng",127.0)
                .addValue("category",category,Types.VARCHAR);
    }

    private List<Long> ids(String method, MapSqlParameterSource parameters) {
        return named.query(query(method),parameters,(row,index) -> row.getLong(1));
    }

    private String query(String name) {
        return Arrays.stream(StoreRepository.class.getDeclaredMethods()).filter(method -> method.getName().equals(name))
                .findFirst().orElseThrow().getAnnotation(Query.class).value();
    }

    private static String normalize(String value) {
        return value == null ? "" : NON_SEARCH.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private boolean legacyMatches(String name, String business, String partner) {
        String normalized = normalize(partner);
        List<String> aliases = switch (normalized) {
            case "gs25", "지에스25" -> List.of("gs25","지에스25");
            case "cu", "씨유" -> List.of("cu","씨유");
            case "세븐일레븐", "7eleven" -> List.of("세븐일레븐","7eleven");
            default -> List.of(normalized);
        };
        return aliases.stream().anyMatch(alias -> !alias.isEmpty() && normalize(name).contains(alias))
                && StorePartnerBusinessPolicy.matches(partner,business);
    }
}
