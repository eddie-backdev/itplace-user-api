package com.itplace.userapi.map.service;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties("app.map.cluster-snapshot")
public class MapClusterSnapshotProperties {
    private boolean enabled = true;
    @Min(1000) private long pollIntervalMs = 5_000;
    @Min(1000) private long refreshIntervalMs = 30_000;
    @Min(1000) private long maximumAgeMs = 60_000;
    @Min(1) @Max(100_000) private int maxRegions = 20_000;
    @Min(1) @Max(500_000) private int maxCategoryCounts = 100_000;
    @Min(1024) @Max(67_108_864) private int maxPayloadBytes = 16_777_216;
    @Min(1) @Max(30) private int queryTimeoutSeconds = 5;
    @Min(1000) @Max(60_000) private int networkTimeoutMs = 10_000;

    @AssertTrue(message = "maximum-age-ms는 refresh-interval-ms와 poll-interval-ms 합계 이상이어야 합니다")
    public boolean isAgeWindowValid() { return maximumAgeMs >= refreshIntervalMs + pollIntervalMs; }
}
