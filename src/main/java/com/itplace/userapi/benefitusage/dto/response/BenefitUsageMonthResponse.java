package com.itplace.userapi.benefitusage.dto.response;

import java.util.List;

public record BenefitUsageMonthResponse(
        String month,
        int recordCount,
        long totalSavedAmountKrw,
        int unknownAmountCount,
        List<BenefitUsageRecordResponse> records
) {
}
