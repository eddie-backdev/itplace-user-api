package com.itplace.userapi.history.service;

import com.itplace.userapi.benefit.dto.response.PagedResponse;
import com.itplace.userapi.history.dto.MembershipHistoryResponse;
import com.itplace.userapi.history.dto.MonthlyDiscountResponse;
import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;

public interface MembershipHistoryService {
    PagedResponse<MembershipHistoryResponse> getUserHistory(
            Long userId,
            String keyword,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    );

    MonthlyDiscountResponse getMonthlyDiscountSummary(Long userId);

    void useMembership(Long userId, Long benefitId, Integer amount, Long storeId);
}

