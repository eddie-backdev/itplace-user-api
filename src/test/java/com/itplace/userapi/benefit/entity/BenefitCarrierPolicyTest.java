package com.itplace.userapi.benefit.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.entity.enums.BenefitPolicyCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenefitCarrierPolicyTest {
    @Test
    void displayLimitKeepsSourceAndEveryDistinctLimitCondition() {
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder()
                .sourceLimitText("하루 1회")
                .conditions(new BenefitConditions(null, null,
                        List.of("하루 1회", "월 결제 3만원 한도", "매장 결제 1만원 이상"), null, null))
                .build();

        assertThat(policy.getDisplayBenefitLimit())
                .isEqualTo("하루 1회\n월 결제 3만원 한도\n매장 결제 1만원 이상");
    }

    @Test
    void legacyUnlimitedCodeDoesNotPromiseUnrestrictedUse() {
        BenefitPolicy unlimited = BenefitPolicy.builder()
                .code(BenefitPolicyCode.UNLIMITED)
                .name("제한 없음")
                .build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitPolicy(unlimited).build();

        assertThat(policy.getDisplayBenefitLimit()).isEqualTo("이용 방법에서 횟수·한도 확인");
    }
}
