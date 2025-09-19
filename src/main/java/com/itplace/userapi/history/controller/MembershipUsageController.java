package com.itplace.userapi.history.controller;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.history.MembershipHistoryCode;
import com.itplace.userapi.history.dto.MembershipUseRequest;
import com.itplace.userapi.history.service.MembershipHistoryService;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequestMapping("/api/v1/membership-history")
@RestController
@RequiredArgsConstructor
public class MembershipUsageController {
    private final MembershipHistoryService membershipHistoryService;

    @PostMapping("/use")
    public ResponseEntity<ApiResponse<Void>> useMembership(
            @AuthenticationPrincipal PrincipalDetails principalDetails,
            @RequestBody MembershipUseRequest request
    ) {
        membershipHistoryService.useMembership(
                principalDetails.getUserId(),
                request.getBenefitId(),
                request.getAmount(),
                request.getStoreId()
        );
        ApiResponse<Void> body = ApiResponse.ok(MembershipHistoryCode.MEMBERSHIP_USE_SUCCESS);
        return new ResponseEntity<>(body, body.getStatus());
    }
}
