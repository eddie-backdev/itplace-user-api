package com.itplace.userapi.event.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.event.dto.response.GiftResponse;
import com.itplace.userapi.event.dto.response.ScratchResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GiftControllerTest {

    private final GiftController giftController = new GiftController();

    @Test
    void getGiftNames_returnsDisabledGoneWithoutGiftData() {
        ResponseEntity<ApiResponse<List<GiftResponse>>> response = giftController.getGiftNames();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("EVENT_DISABLED");
        assertThat(response.getBody().getData()).isEmpty();
    }

    @Test
    void scratch_returnsDisabledGoneWithoutGiftMutationPayload() {
        ResponseEntity<ApiResponse<ScratchResult>> response = giftController.scratch();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("EVENT_DISABLED");
        assertThat(response.getBody().getData().isSuccess()).isFalse();
        assertThat(response.getBody().getData().getGift()).isNull();
    }

    @Test
    void getCouponHistory_returnsDisabledGoneWithoutHistoryData() {
        ResponseEntity<ApiResponse<?>> response = giftController.getCouponHistory("SUCCESS");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("EVENT_DISABLED");
        assertThat(response.getBody().getData()).isEqualTo(List.of());
    }
}
