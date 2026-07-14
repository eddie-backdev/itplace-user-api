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
    void clusterQuery_groupsByAdministrativeHierarchyWithGridFallback() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql)
                .contains(
                        "NULLIF(BTRIM(s.city), '')",
                        "NULLIF(BTRIM(s.town), '')",
                        "NULLIF(BTRIM(s.legalDong), '')",
                        "regexp_split_to_array",
                        "generate_subscripts",
                        "string_agg(address_parts.parts[town_index], ' ' ORDER BY town_index)",
                        ":administrativeUnitType = 'CITY' THEN ''",
                        ":administrativeUnitType <> 'CITY'",
                        "ORDER BY part_index DESC",
                        "AS address_is_full",
                        "WHEN '서울특별시' THEN '서울'",
                        "WHEN '경기도' THEN '경기'",
                        "administrative_city.address_is_full",
                        ":administrativeUnitType = 'LEGAL_DONG'",
                        ":administrativeUnitType IN ('LEGAL_DONG', 'TOWN')",
                        "town ~ '(동|읍|면|리|가)$'",
                        "town ~ '(시|군|구|읍|면)$'",
                        "CONCAT_WS('|', city, town, legal_dong)",
                        "CONCAT_WS('|', city, town)",
                        "FLOOR(ST_X(geom) / :gridSizeMeters)",
                        "FLOOR(ST_Y(geom) / :gridSizeMeters)",
                        "CONCAT('grid|', grid_x, '|', grid_y)",
                        "'GRID'",
                        "MD5(region_summary.region_key)",
                        "GROUP BY region_type, region_key",
                        "s.location && ST_MakeEnvelope"
                )
                .doesNotContain(
                        "ST_ClusterDBSCAN",
                        "ST_ClusterKMeans",
                        "LIMIT :clusterLimit",
                        "parts[2:",
                        ":administrative_dong.position"
                );
    }

    @Test
    void clusterQuery_usesFixedAdministrativeAnchorAndDeterministicGridCenter() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql)
                .contains(
                        "s.latitude::double precision AS latitude",
                        "s.longitude::double precision AS longitude",
                        "MIN(latitude) AS singleton_latitude",
                        "MIN(longitude) AS singleton_longitude",
                        "AVG(map_x) AS centroid_x",
                        "AVG(map_y) AS centroid_y",
                        "MIN(grid_x) AS grid_x",
                        "MIN(grid_y) AS grid_y",
                        "LEFT JOIN map_region_anchor region_anchor",
                        "region_anchor.region_type = region_summary.region_type",
                        "region_anchor.region_key = region_summary.region_key",
                        "WHEN region_anchor.latitude IS NOT NULL",
                        "WHEN region_anchor.longitude IS NOT NULL",
                        "WHEN region_summary.region_type = 'GRID'",
                        "(grid_x + 0.5) * :gridSizeMeters",
                        "(grid_y + 0.5) * :gridSizeMeters",
                        "WHEN store_count = 1 THEN singleton_latitude",
                        "WHEN store_count = 1 THEN singleton_longitude",
                        "ST_SetSRID(ST_MakePoint(centroid_x, centroid_y), 3857)"
                )
                .doesNotContain(
                        "ROW_NUMBER() OVER",
                        "POWER(classified.map_x - summary.centroid_x, 2)"
                )
                .containsSubsequence(
                        "WHEN region_anchor.latitude IS NOT NULL",
                        "WHEN region_summary.region_type = 'GRID'",
                        "WHEN store_count = 1 THEN singleton_latitude"
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
                "administrativeUnitType",
                "gridSizeMeters"
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
