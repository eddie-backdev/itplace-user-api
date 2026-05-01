package com.itplace.userapi.event.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.event.GiftCode;
import com.itplace.userapi.event.dto.response.GiftResponse;
import com.itplace.userapi.event.dto.response.HistoryResponse;
import com.itplace.userapi.event.dto.response.ScratchResult;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name = "Event", description = "이벤트 기능 관련 API")
@RestController
@RequestMapping("/api/v1/gifts")
public class GiftController {

    @GetMapping
    public ResponseEntity<ApiResponse<List<GiftResponse>>> getGiftNames() {
        ApiResponse<List<GiftResponse>> body = ApiResponse.of(GiftCode.EVENT_DISABLED, List.of());
        return new ResponseEntity<>(body, GiftCode.EVENT_DISABLED.getStatus());
    }

    @PostMapping("/scratch")
    public ResponseEntity<ApiResponse<ScratchResult>> scratch() {
        ScratchResult result = new ScratchResult(false, GiftCode.EVENT_DISABLED.getMessage(), null);
        ApiResponse<ScratchResult> response = ApiResponse.of(GiftCode.EVENT_DISABLED, result);
        return new ResponseEntity<>(response, GiftCode.EVENT_DISABLED.getStatus());
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<?>> getCouponHistory(
            @RequestParam(required = false) String type) {
        ApiResponse<List<HistoryResponse>> body = ApiResponse.of(GiftCode.EVENT_DISABLED, List.of());
        return new ResponseEntity<>(body, GiftCode.EVENT_DISABLED.getStatus());
    }
}
