package com.itplace.userapi.map.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
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
    void clusterQuery_usesGloballyAnchoredGridCells() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql)
                .contains(
                        "FLOOR(ST_X(geom) / :gridSizeMeters)",
                        "FLOOR(ST_Y(geom) / :gridSizeMeters)",
                        "CONCAT('g:', :mapLevel, ':', grid_x, ':', grid_y)",
                        "GROUP BY grid_x, grid_y",
                        "s.location && ST_MakeEnvelope"
                )
                .doesNotContain("ST_ClusterDBSCAN", "ST_ClusterKMeans");
    }

    @Test
    void clusterQuery_usesExactSingletonCoordinatesAndProjectedCentroids() {
        String sql = queryValue("findStoreClustersInView");

        assertThat(sql)
                .contains(
                        "s.latitude::double precision AS latitude",
                        "s.longitude::double precision AS longitude",
                        "AVG(map_x) AS centroid_x",
                        "AVG(map_y) AS centroid_y",
                        "WHEN store_count = 1 THEN singleton_latitude",
                        "WHEN store_count = 1 THEN singleton_longitude",
                        "ST_SetSRID(ST_MakePoint(centroid_x, centroid_y), 3857)"
                )
                .doesNotContain(
                        "(grid_x + 0.5) * :gridSizeMeters",
                        "(grid_y + 0.5) * :gridSizeMeters"
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
