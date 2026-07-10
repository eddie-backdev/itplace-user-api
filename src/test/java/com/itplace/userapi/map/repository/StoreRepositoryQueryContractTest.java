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

    private String queryValue(String methodName) {
        Method method = Arrays.stream(StoreRepository.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return method.getAnnotation(Query.class).value();
    }
}
