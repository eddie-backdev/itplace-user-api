package com.itplace.userapi.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DataSourceConfigFlywayTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(DataSourceConfig.class)
            .withPropertyValues(
                    "spring.datasource.source.jdbc-url=jdbc:postgresql://localhost/source",
                    "spring.datasource.source.username=source-user",
                    "spring.datasource.source.password=source-password",
                    "spring.datasource.source.pool-name=source-pool",
                    "spring.datasource.source.maximum-pool-size=17",
                    "spring.datasource.source.minimum-idle=3",
                    "spring.datasource.replica.jdbc-url=jdbc:postgresql://localhost/replica",
                    "spring.datasource.replica.username=replica-user",
                    "spring.datasource.replica.password=replica-password",
                    "spring.datasource.replica.pool-name=replica-pool",
                    "spring.datasource.replica.maximum-pool-size=41",
                    "spring.datasource.replica.minimum-idle=7"
            );

    @Test
    void flywayUsesSourceDataSourceInsteadOfReadReplicaRouting() throws NoSuchMethodException {
        Method sourceDataSource = DataSourceConfig.class.getDeclaredMethod("sourceDataSource");

        assertThat(sourceDataSource.isAnnotationPresent(FlywayDataSource.class)).isTrue();
    }

    @Test
    void bindsHikariPoolSettingsToBothRoutingDataSources() {
        contextRunner.run(context -> {
            HikariDataSource source = context.getBean("sourceDataSource", HikariDataSource.class);
            HikariDataSource replica = context.getBean("replicaDataSource", HikariDataSource.class);

            assertThat(source.getPoolName()).isEqualTo("source-pool");
            assertThat(source.getMaximumPoolSize()).isEqualTo(17);
            assertThat(source.getMinimumIdle()).isEqualTo(3);
            assertThat(replica.getPoolName()).isEqualTo("replica-pool");
            assertThat(replica.getMaximumPoolSize()).isEqualTo(41);
            assertThat(replica.getMinimumIdle()).isEqualTo(7);
        });
    }
}
