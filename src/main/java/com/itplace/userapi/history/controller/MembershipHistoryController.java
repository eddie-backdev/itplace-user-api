package com.itplace.userapi.history.controller;

import com.itplace.userapi.benefit.dto.response.PagedResponse;
import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.history.MembershipHistoryCode;
import com.itplace.userapi.history.dto.MembershipHistoryResponse;
import com.itplace.userapi.history.dto.MonthlyDiscountResponse;
import com.itplace.userapi.history.exception.UnauthorizedAccessException;
import com.itplace.userapi.history.service.MembershipHistoryService;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/api/v1/membership-history")
@RestController
@RequiredArgsConstructor
public class MembershipHistoryController {
    private final MembershipHistoryService membershipHistoryService;

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<MembershipHistoryResponse>>> getHistory(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        if (principalDetails == null) {
            throw new UnauthorizedAccessException(MembershipHistoryCode.UNAUTHORIZED_MEMBERSHIP_ACCESS);
        }
        Pageable pageable = PageRequest.of(page, size);
        PagedResponse<MembershipHistoryResponse> result =
                membershipHistoryService.getUserHistory(principalDetails.getUserId(), keyword, startDate, endDate,
                        pageable);
        ApiResponse<PagedResponse<MembershipHistoryResponse>> body = ApiResponse.of(
                MembershipHistoryCode.MEMBERSHIP_HISTORY_SUCCESS, result);
        return new ResponseEntity<>(body, body.getStatus());
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<MonthlyDiscountResponse>> getMonthlyDiscountSummary(
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        if (principalDetails == null) {
            throw new UnauthorizedAccessException(MembershipHistoryCode.UNAUTHORIZED_MEMBERSHIP_ACCESS);
        }
        MonthlyDiscountResponse result = membershipHistoryService.getMonthlyDiscountSummary(
                principalDetails.getUserId());
        ApiResponse<MonthlyDiscountResponse> body = ApiResponse.of(
                MembershipHistoryCode.MEMBERSHIP_HISTORY_SUMMARY_SUCCESS,
                result);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
