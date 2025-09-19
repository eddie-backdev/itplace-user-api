package com.itplace.userapi.log.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RankResult {
    private long id; // 대시보드 - partnerId, 혜택목록 - benefitId
    private long count;
}
