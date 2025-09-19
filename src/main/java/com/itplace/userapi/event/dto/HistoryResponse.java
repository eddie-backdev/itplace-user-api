package com.itplace.userapi.event.dto;

import com.itplace.userapi.event.entity.ResultType;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HistoryResponse {
    private Long userId;
    private Long historyId;
    private String giftName;
    private ResultType result;
    private LocalDate usedDate;
}
