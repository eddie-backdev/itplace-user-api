package com.itplace.userapi.user.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class UserCouponControllerTest {

    @Test
    void getMyCouponCount_returnsZeroDisabledReadOnlyCompatibilityResponse() {
        UserCouponController controller = new UserCouponController();

        ResponseEntity<ApiResponse<Integer>> response = controller.getMyCouponCount();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("COUPON_EVENT_DISABLED");
        assertThat(response.getBody().getData()).isZero();
    }
}
