package com.itplace.userapi.map.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class MapSchedulingConfig {
    @Bean
    ThreadPoolTaskScheduler mapSummaryScheduler() { return scheduler("map-summary-"); }

    @Bean
    ThreadPoolTaskScheduler mapSnapshotScheduler() { return scheduler("map-snapshot-"); }

    @Bean
    ThreadPoolTaskScheduler taskScheduler() { return scheduler("scheduled-"); }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
