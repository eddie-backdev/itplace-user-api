package com.itplace.userapi.map.service;

import java.time.LocalDate;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import com.itplace.userapi.map.repository.MapPartnerViewRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MapPartnerViewRetentionJob {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final MapPartnerViewRepository viewRepository;

    @Value("${app.map.partner-view-retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")
    public void deleteExpiredViews() {
        viewRepository.deleteOlderThan(LocalDate.now(SEOUL).minusDays(Math.max(30, retentionDays)));
    }
}
