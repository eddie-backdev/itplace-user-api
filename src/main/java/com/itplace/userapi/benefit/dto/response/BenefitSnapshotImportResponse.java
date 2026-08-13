package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BenefitSnapshotImportResponse {
    private Carrier carrier;
    private int receivedCount;
    private int upsertedBenefitCount;
    private int tierBenefitCount;
    private BenefitSnapshotImportStatus status;
    private LocalDateTime lastAppliedCrawledAt;
}
