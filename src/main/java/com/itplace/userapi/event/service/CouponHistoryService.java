package com.itplace.userapi.event.service;

import com.itplace.userapi.event.dto.HistoryResponse;
import java.util.List;

public interface CouponHistoryService {
    List<HistoryResponse> getCouponHistory(Long userId, String type);
}
