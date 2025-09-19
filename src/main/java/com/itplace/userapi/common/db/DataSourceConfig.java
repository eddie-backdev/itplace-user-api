package com.itplace.userapi.common.db;

import com.zaxxer.hikari.HikariDataSource;
import java.util.HashMap;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

@Configuration
@RequiredArgsConstructor
@EnableJpaRepositories
public class DataSourceConfig {

    // Write replica 정보로 만든 DataSource
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.source")
    public DataSource sourceDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    // Read replica 정보로 만든 DataSource
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.replica")
    public DataSource replicaDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    // 읽기 모드인지 여부로 DataSource 분기 처리
    @Bean
    @DependsOn({"sourceDataSource", "replicaDataSource"})
    public DataSource routeDataSource() {
        DataSourceRouter dataSourceRouter = new DataSourceRouter();
        DataSource sourceDataSource = sourceDataSource();
        DataSource replicaDataSource = replicaDataSource();

        HashMap<Object, Object> dataSourceMap = new HashMap<>();
        dataSourceMap.put("source", sourceDataSource);
        dataSourceMap.put("replica", replicaDataSource);
        dataSourceRouter.setTargetDataSources(dataSourceMap);
        dataSourceRouter.setDefaultTargetDataSource(sourceDataSource);
        return dataSourceRouter;
    }

    @Bean
    @Primary
    @DependsOn({"routeDataSource"})
    public DataSource dataSource() {
        return new LazyConnectionDataSourceProxy(routeDataSource());
    }

}