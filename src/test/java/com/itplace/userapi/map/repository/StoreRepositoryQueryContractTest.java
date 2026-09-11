package com.itplace.userapi.map.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.hibernate.query.sql.internal.ParameterParser;
import org.hibernate.query.sql.spi.ParameterRecognizer;
import org.springframework.data.jpa.repository.Query;

class StoreRepositoryQueryContractTest {

    @Test
    void distributedMapQuery_usesOneDeterministicGridRankingQuery() {
        String sql = queryValue("findDistributedStoreIdsWithinRadius");

        assertThat(sql)
                .contains(
                        "s.location && ST_MakeEnvelope",
                        "ST_DWithin",
                        "FLOOR",
                        "ROW_NUMBER() OVER",
                        "PARTITION BY grid_y, grid_x"
                )
                .doesNotContain("RANDOM()", "ST_ClusterDBSCAN", "ST_ClusterKMeans");
    }

    @Test
    void clusterQuery_readsPrecomputedAdministrativeRegionSummaryInsideViewport() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql)
                .contains(
                        "WITH summarized_region AS MATERIALIZED",
                        "FROM map_region_store_summary summary",
                        "JOIN map_region_anchor region_anchor",
                        "summary.aggregation_unit = :administrativeUnitType",
                        "SUM(summary.store_count)",
                        "region_anchor.longitude BETWEEN :minLng AND :maxLng",
                        "region_anchor.latitude BETWEEN :minLat AND :maxLat"
                )
                .doesNotContain(
                        "FROM store s",
                        "JOIN map_store_cluster_region",
                        "WITH visible_region AS",
                        "ST_MakeEnvelope",
                        "selected_region AS MATERIALIZED",
                        "region_summary AS",
                        "regexp_split_to_array",
                        "generate_subscripts",
                        "ST_Transform",
                        ":gridSizeMeters",
                        "'GRID'",
                        "ST_ClusterDBSCAN",
                        "ST_ClusterKMeans",
                        "LIMIT :clusterLimit"
                );
    }

    @Test
    void clusterQuery_countsEntireVisibleAdministrativeRegionFromSummary() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql).contains(
                "FROM map_region_store_summary summary",
                "summary.aggregation_unit = :administrativeUnitType",
                "CAST(SUM(summary.store_count) AS BIGINT)"
        );
    }

    @Test
    void clusterQuery_returnsOnlyFixedAdministrativeAnchors() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql)
                .contains(
                        "JOIN map_region_anchor region_anchor",
                        "region_anchor.region_type = summary.region_type",
                        "region_anchor.region_key = summary.region_key",
                        "summarized.latitude AS \"latitude\"",
                        "summarized.longitude AS \"longitude\""
                )
                .doesNotContain(
                        "LEFT JOIN map_region_anchor",
                        "AVG(map_x)",
                        "AVG(map_y)",
                        "singleton_latitude",
                        "singleton_longitude"
                );
    }

    @Test
    void regionAnchorMigration_preservesExistingAnchorsOnRepeatedExecution() throws IOException {
        String sql;
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V20260815_0004__add_map_region_anchor.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains(
                "CREATE TABLE IF NOT EXISTS map_region_anchor",
                "PRIMARY KEY (region_type, region_key)",
                "CHECK (region_type IN ('CITY', 'TOWN', 'LEGAL_DONG'))",
                "'STORE_CENTROID'",
                "ON CONFLICT (region_type, region_key) DO NOTHING"
        );
    }

    @Test
    void storeClusterRegionMigration_backfillsAndSynchronizesPrecomputedRegions() throws IOException {
        String sql;
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V20260815_0005__add_map_store_cluster_region.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains(
                "CREATE TABLE IF NOT EXISTS map_store_cluster_region",
                "CREATE OR REPLACE FUNCTION resolve_map_store_cluster_region",
                "CREATE OR REPLACE FUNCTION sync_map_store_cluster_region",
                "CREATE TRIGGER trg_sync_map_store_cluster_region",
                "city_region_hash CHAR(32)",
                "town_region_hash CHAR(32)",
                "legal_dong_region_hash CHAR(32)",
                "GENERATED ALWAYS AS (MD5",
                "ON CONFLICT (store_id) DO UPDATE SET",
                "ON CONFLICT (region_type, region_key) DO NOTHING",
                "ANALYZE map_store_cluster_region"
        );
    }

    @Test
    void regionStoreSummaryMigration_precomputesAndRefreshesClusterCounts() throws IOException {
        String sql;
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V20260806_0001__add_map_region_store_summary.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains(
                "CREATE MATERIALIZED VIEW map_region_store_summary",
                "'CITY'::VARCHAR(20) AS aggregation_unit",
                "'TOWN'::VARCHAR(20) AS aggregation_unit",
                "'LEGAL_DONG'::VARCHAR(20) AS aggregation_unit",
                "COUNT(*)::BIGINT AS store_count",
                "CREATE UNIQUE INDEX uq_map_region_store_summary_lookup",
                "CREATE TABLE map_region_store_summary_state"
        );
    }

    @Test
    void regionStoreSummary_excludesDaracPlacesOutsideStorageBusiness() throws IOException {
        String sql;
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V20260806_0001__add_map_region_store_summary.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains(
                "REGEXP_REPLACE(",
                "LOWER(COALESCE(p.partnerName, ''))",
                "NOT IN ('다락', '미니창고다락')",
                "s.business LIKE '%보관%'",
                "s.business LIKE '%저장%'"
        );
    }

    @Test
    void previewQuery_appliesStoredNameAndBusinessEligibilityBeforeLimit() {
        assertThat(queryValue("findStorePreviewsInView"))
                .containsSubsequence(
                        "map_store_partner_matches(s.mapNormalizedName, s.mapNormalizedBusiness, p.mapNormalizedName)",
                        "ORDER BY",
                        "LIMIT :limit"
                )
                .doesNotContain("REGEXP_REPLACE", "LOWER(COALESCE(p.partnerName");
    }

    @Test
    void previewQuery_separatesSpatialJoinFromExactNumericBounds() {
        String sql = queryValue("findStorePreviewsInView");

        assertThat(sql).contains(
                "s.location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)",
                "WITH visible_store AS MATERIALIZED",
                "numeric_longitude BETWEEN :minLng AND :maxLng",
                "numeric_latitude BETWEEN :minLat AND :maxLat"
        );
    }

    @Test
    void candidatePartnerQueryRanksNearbyStoresForAllPartnersInOneSql() {
        String sql = queryValue("searchNearbyStoreIdsByPartnerIds");

        assertThat(sql)
                .contains(
                        "s.partnerId IN :partnerIds",
                        "ROW_NUMBER() OVER",
                        "PARTITION BY s.partnerId",
                        "row_num <= 30"
                );
    }

    @Test
    void mapCandidateQueries_requireActiveStoreAndActiveOfflineBenefit() {
        List<String> mapQueryMethods = List.of(
                "findStoreIdsInRadius",
                "findStoreIdsByCategoryWithinRadius",
                "findDistributedStoreIdsWithinRadius",
                "findStorePreviewsInView",
                "searchNearbyStoreIds",
                "searchNearbyStoreIdsByPartnerId",
                "searchNearbyStoreIdsByPartnerIds",
                "findEligibleStoreIdsWithinRadius",
                "searchEligibleNearbyStoreIds"
        );

        mapQueryMethods.forEach(methodName -> assertThat(queryValue(methodName))
                .as(methodName)
                .contains(
                        "s.active = true",
                        "b.active = true",
                        "bcp.active = true",
                        "bcp.usageType IN ('offline', 'both')"
                ));
    }

    @Test
    void clusterLookupMigration_indexesViewportAndSummaryJoin() throws IOException {
        String sql;
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V20260904_0001__index_map_region_cluster_lookup.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains(
                "CREATE INDEX IF NOT EXISTS idx_map_region_anchor_viewport",
                "ON map_region_anchor (latitude, longitude)",
                "CREATE INDEX IF NOT EXISTS idx_map_region_store_summary_cluster_lookup",
                "ON map_region_store_summary (aggregation_unit, category, region_type, region_key)",
                "ANALYZE map_region_anchor",
                "ANALYZE map_region_store_summary"
        );
    }

    @Test
    void finalStoreLoads_repeatVisibilityGuardForStaleSearchIds() {
        assertThat(queryValue("findAllByStoreIdInWithPartner")).contains(
                "s.active = true",
                "b.active = true",
                "policy.active = true",
                "UsageType.OFFLINE",
                "UsageType.BOTH"
        );
    }

    @Test
    void geodataLifecycleMigration_isIdempotentAndUsesSoftDeactivationFields() throws IOException {
        String sql;
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V20260722_2326__add_store_geodata_lifecycle.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains(
                "ADD COLUMN IF NOT EXISTS sourceProvider",
                "ADD COLUMN IF NOT EXISTS sourcePlaceId",
                "ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE",
                "ADD COLUMN IF NOT EXISTS lastSeenRunId",
                "ADD COLUMN IF NOT EXISTS healthyMissCount",
                "CREATE UNIQUE INDEX IF NOT EXISTS uq_store_kakao_partner_place",
                "WHERE sourceProvider = 'KAKAO' AND sourcePlaceId IS NOT NULL",
                "CREATE INDEX IF NOT EXISTS idx_benefit_active_partner",
                "CREATE INDEX IF NOT EXISTS idx_benefit_policy_active_offline"
        ).doesNotContain("DELETE FROM store");
    }

    @Test
    void clusterQuery_exposesOnlyDeclaredHibernateNamedParameters() {
        Set<String> namedParameters = new LinkedHashSet<>();

        ParameterParser.parse(queryValue("findStoreClustersInView"), new ParameterRecognizer() {
            @Override
            public void ordinalParameter(int sourcePosition) {
                throw new AssertionError("Unexpected ordinal parameter at " + sourcePosition);
            }

            @Override
            public void namedParameter(String name, int sourcePosition) {
                namedParameters.add(name);
            }

            @Override
            public void jpaPositionalParameter(int label, int sourcePosition) {
                throw new AssertionError("Unexpected positional parameter at " + sourcePosition);
            }

            @Override
            public void other(char character) {
                // SQL literals and operators are intentionally ignored.
            }
        });

        assertThat(namedParameters).containsExactlyInAnyOrder(
                "minLat",
                "maxLat",
                "minLng",
                "maxLng",
                "category",
                "mapLevel",
                "administrativeUnitType"
        );
    }

    @Test
    void everyDeclaredQueryHasShortReadOnlyTransaction() {
        Arrays.stream(StoreRepository.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Query.class))
                .forEach(method -> {
                    var transaction = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
                    assertThat(transaction).as(method.getName()).isNotNull();
                    assertThat(transaction.readOnly()).isTrue();
                    assertThat(transaction.timeout()).isEqualTo(5);
                });
    }

    private String queryValue(String methodName) {
        Method method = Arrays.stream(StoreRepository.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return method.getAnnotation(Query.class).value();
    }
}
