package com.itplace.userapi.benefit.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BenefitContextSplitterTest {

    @Test
    void split_extractsOnlineAndOfflineContextFromMixedText() {
        BenefitContextSplitter.SplitContext result = BenefitContextSplitter.split(
                "온라인: 25% 할인 / 오프라인: 25% 할인"
        );

        assertThat(result.onlineContext()).isEqualTo("25% 할인");
        assertThat(result.offlineContext()).isEqualTo("25% 할인");
    }

    @Test
    void split_returnsNullChannelsWhenTextIsNotChannelSeparated() {
        BenefitContextSplitter.SplitContext result = BenefitContextSplitter.split(
                "VVIP/VIP : 20% 할인(최대 4만원 할인) 우수 : 15% 할인(최대 3만원 할인)"
        );

        assertThat(result.onlineContext()).isNull();
        assertThat(result.offlineContext()).isNull();
    }
}
