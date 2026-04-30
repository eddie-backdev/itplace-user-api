package com.itplace.userapi.user.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import org.junit.jupiter.api.Test;

class MembershipProfileValidatorTest {

    @Test
    void acceptsCarrierScopedGradePairs() {
        assertThat(MembershipProfileValidator.isValid(Carrier.LGU, Grade.VIP)).isTrue();
        assertThat(MembershipProfileValidator.isValid(Carrier.SKT, Grade.SKT_VIP)).isTrue();
        assertThat(MembershipProfileValidator.isValid(Carrier.KT, Grade.KT_VVIP)).isTrue();
    }

    @Test
    void rejectsCrossCarrierPlainGrades() {
        assertThat(MembershipProfileValidator.isValid(Carrier.SKT, Grade.VIP)).isFalse();
        assertThat(MembershipProfileValidator.isValid(Carrier.KT, Grade.VVIP)).isFalse();
        assertThat(MembershipProfileValidator.isValid(null, Grade.VIP)).isFalse();
        assertThat(MembershipProfileValidator.isValid(Carrier.LGU, null)).isFalse();
    }
}
