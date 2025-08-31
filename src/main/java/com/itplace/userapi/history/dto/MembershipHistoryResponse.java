package com.itplace.userapi.history.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipHistoryResponse {
    private String image;
    private String benefitName;
    private Long discountAmount;
    private LocalDateTime usedAt;
}
