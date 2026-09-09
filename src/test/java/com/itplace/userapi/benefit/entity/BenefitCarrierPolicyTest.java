package com.itplace.userapi.benefit.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import org.junit.jupiter.api.Test;

class BenefitCarrierPolicyTest {

    @Test
    void unknownOrFallbackPolicyDoesNotPromiseUnlimitedUse() {
        BenefitCarrierPolicy policy = new BenefitCarrierPolicy();
        assertThat(policy.getDisplayBenefitLimit()).isEqualTo("이용 방법에서 횟수·한도 확인");

        policy.setBenefitPolicy(BenefitPolicy.builder()
                .code(BenefitPolicyCode.UNLIMITED).name("제한없음").build());
        assertThat(policy.getDisplayBenefitLimit()).isEqualTo("이용 방법에서 횟수·한도 확인");

        policy.setBenefitPolicy(BenefitPolicy.builder()
                .code(BenefitPolicyCode.DAILY_ONCE).name("일 1회").build());
        assertThat(policy.getDisplayBenefitLimit()).isEqualTo("일 1회");
    }

    @Test
    void publicUrlPreservesRedemptionLinkAndFallsBackToCarrierSource() {
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder()
                .sourceUrl(" https://carrier.example/benefit/123 ")
                .url(" https://partner.example/ ")
                .build();
        assertThat(policy.getPublicUrl()).isEqualTo("https://partner.example/");

        policy.setUrl(" ");
        assertThat(policy.getPublicUrl()).isEqualTo("https://carrier.example/benefit/123");
        policy.setUrl("https://partner.example/");
        policy.setSourceUrl(" ");
        assertThat(policy.getPublicUrl()).isEqualTo("https://partner.example/");

        policy.setUrl(null);
        assertThat(policy.getPublicUrl()).isNull();
        policy.setUrl(" ");
        assertThat(policy.getPublicUrl()).isNull();
    }
}
