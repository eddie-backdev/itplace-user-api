package com.itplace.userapi.benefitusage.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateBenefitUsageRecordRequest(
        @NotNull @Positive Long benefitId,
        @NotNull LocalDate usedAt,
        @Min(0) @Max(100_000_000) @Digits(integer = 9, fraction = 0) BigDecimal savedAmountKrw,
        @Size(max = 200) String memo
) {
    public CreateBenefitUsageRecordRequest {
        if (memo != null) {
            memo = memo.trim();
            if (memo.isEmpty()) memo = null;
        }
    }
}
