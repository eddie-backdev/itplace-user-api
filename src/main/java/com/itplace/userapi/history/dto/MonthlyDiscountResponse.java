package com.itplace.userapi.history.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class MonthlyDiscountResponse {
    private Long userId;
    private String yearMonth;         // "YYYY-MM"
    private Long totalDiscountAmount;
}
