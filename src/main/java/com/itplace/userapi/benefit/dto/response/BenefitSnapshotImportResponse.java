package com.itplace.userapi.benefit.dto.response;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BenefitSnapshotImportResponse {
    private Carrier carrier;
    private int receivedCount;
    private int upsertedBenefitCount;
    private int tierBenefitCount;
}
