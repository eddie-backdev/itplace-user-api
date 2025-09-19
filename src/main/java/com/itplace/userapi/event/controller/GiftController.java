package com.itplace.userapi.event.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.event.GiftCode;
import com.itplace.userapi.event.dto.GiftResponse;
import com.itplace.userapi.event.dto.HistoryResponse;
import com.itplace.userapi.event.dto.ScratchResult;
import com.itplace.userapi.event.service.CouponHistoryService;
import com.itplace.userapi.event.service.GiftService;
import com.itplace.userapi.event.service.ScratchService;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gifts")
@RequiredArgsConstructor
public class GiftController {
    private final GiftService giftService;
    private final ScratchService scratchService;
    private final CouponHistoryService couponHistoryService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<GiftResponse>>> getGiftNames() {
        List<GiftResponse> gifts = giftService.getAllGiftNames();

        ApiResponse<List<GiftResponse>> body = ApiResponse.of(GiftCode.GIFT_LIST, gifts);

        return new ResponseEntity<>(body, body.getStatus());

    }

    @PostMapping("/scratch")
    public ResponseEntity<ApiResponse<ScratchResult>> scratch(
            @AuthenticationPrincipal PrincipalDetails principalDetails
    ) {
        Long userId = principalDetails.getUserId();
        ScratchResult result = scratchService.scratch(userId);

        GiftCode code;
        if (!result.isSuccess() && "별이 부족합니다. 별을 다시 모은 후 시도해주세요.".equals(result.getMessage())) {
            code = GiftCode.COUPON_LACK;
        } else {
            code = GiftCode.SCRATCH_SUCCESS;
        }

        ApiResponse<ScratchResult> response = ApiResponse.of(code, result);
        return new ResponseEntity<>(response, code.getStatus());
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<?>> getCouponHistory(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @RequestParam(required = false) String type) {
        Long userId = principalDetails.getUserId();
        List<HistoryResponse> historyList = couponHistoryService.getCouponHistory(userId, type);
        ApiResponse<?> body = ApiResponse.of(GiftCode.COUPON_HISTORY_SUCCESS, historyList);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
