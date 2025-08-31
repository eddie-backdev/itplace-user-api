package com.itplace.userapi.history.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MembershipUseRequest {
    private Long benefitId;
    private Integer amount;
    private Long storeId;
}
