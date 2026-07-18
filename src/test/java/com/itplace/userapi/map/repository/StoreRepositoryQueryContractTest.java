package com.itplace.userapi.map.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
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
    void clusterQuery_groupsPrecomputedAdministrativeRegionsInsideViewport() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql)
                .contains(
                        "WITH selected_region AS MATERIALIZED",
                        "FROM map_store_cluster_region mapped",
                        "JOIN selected_region region ON region.store_id = s.storeId",
                        "CASE :administrativeUnitType",
                        "mapped.city_region_key",
                        "mapped.town_region_key",
                        "mapped.legal_dong_region_key",
                        "mapped.city_region_hash",
                        "mapped.town_region_hash",
                        "mapped.legal_dong_region_hash",
                        "GROUP BY region.region_type, region.region_hash",
                        "region_summary.region_hash",
                        "CAST(:minLng AS NUMERIC)",
                        "CAST(:minLat AS NUMERIC)",
                        "s.location && ST_MakeEnvelope"
                )
                .doesNotContain(
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
    void clusterQuery_returnsOnlyFixedAdministrativeAnchors() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql)
                .contains(
                        "JOIN map_region_anchor region_anchor",
                        "region_anchor.region_type = region_summary.region_type",
                        "region_anchor.region_key = region_summary.region_key",
                        "region_anchor.latitude AS \"latitude\"",
                        "region_anchor.longitude AS \"longitude\""
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
        try (InputStream input = getClass().getResourceAsStream("/db/map_region_anchor.sql")) {
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
        try (InputStream input = getClass().getResourceAsStream("/db/map_store_cluster_region.sql")) {
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
    void clusterQuery_excludesDaracPlacesOutsideStorageBusiness() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql).contains(
                "REGEXP_REPLACE(",
                "LOWER(COALESCE(p.partnerName, ''))",
                "NOT IN ('다락', '미니창고다락')",
                "s.business LIKE '%보관%'",
                "s.business LIKE '%저장%'"
        );
    }

    @Test
    void previewQuery_excludesDaracPlacesOutsideStorageBusinessBeforeLimit() {
        String sql = queryValue("findStorePreviewsInView");

        assertThat(sql)
                .contains(
                        "REGEXP_REPLACE(",
                        "LOWER(COALESCE(p.partnerName, ''))",
                        "NOT IN ('다락', '미니창고다락')",
                        "s.business LIKE '%보관%'",
                        "s.business LIKE '%저장%'"
                )
                .containsSubsequence(
                        "NOT IN ('다락', '미니창고다락')",
                        "ORDER BY",
                        "LIMIT :limit"
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

    private String queryValue(String methodName) {
        Method method = Arrays.stream(StoreRepository.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return method.getAnnotation(Query.class).value();
    }
}
