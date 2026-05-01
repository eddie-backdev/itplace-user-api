package com.itplace.userapi.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScratchServiceImplTest {

    @Test
    void scratch_returnsDisabledResultWithoutMutatingEventState() {
        ScratchServiceImpl scratchService = new ScratchServiceImpl();

        var result = scratchService.scratch(1L);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("이벤트가 종료되었습니다.");
        assertThat(result.getGift()).isNull();
    }
}
