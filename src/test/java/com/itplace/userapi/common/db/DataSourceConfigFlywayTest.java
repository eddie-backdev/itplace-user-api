package com.itplace.userapi.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;

class DataSourceConfigFlywayTest {

    @Test
    void flywayUsesSourceDataSourceInsteadOfReadReplicaRouting() throws NoSuchMethodException {
        Method sourceDataSource = DataSourceConfig.class.getDeclaredMethod("sourceDataSource");

        assertThat(sourceDataSource.isAnnotationPresent(FlywayDataSource.class)).isTrue();
    }
}
