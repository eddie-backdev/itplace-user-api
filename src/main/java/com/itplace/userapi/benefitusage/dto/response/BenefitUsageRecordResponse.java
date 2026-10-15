package com.itplace.userapi.benefitusage.dto.response;

import com.itplace.userapi.benefitusage.entity.BenefitUsageRecord;
import java.time.LocalDate;

public record BenefitUsageRecordResponse(
        Long recordId,
        Long benefitId,
        Long partnerId,
        String benefitName,
        String partnerName,
        String partnerImage,
        LocalDate usedAt,
        Long savedAmountKrw,
        String memo
) {
    public static BenefitUsageRecordResponse from(BenefitUsageRecord record) {
        return new BenefitUsageRecordResponse(
                record.getRecordId(), record.getBenefitId(), record.getPartnerId(),
                record.getBenefitName(), record.getPartnerName(), record.getPartnerImage(),
                record.getUsedAt(), record.getSavedAmountKrw(), record.getMemo());
    }
}
