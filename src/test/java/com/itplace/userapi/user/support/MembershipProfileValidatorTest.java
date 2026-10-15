package com.itplace.userapi.user.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import org.junit.jupiter.api.Test;

class MembershipProfileValidatorTest {

    @Test
    void signupAcceptsBothFieldsOmitted() {
        assertThat(MembershipProfileValidator.isValidForSignUp(null, null)).isTrue();
    }

    @Test
    void signupAcceptsValidCarrierAndGradePair() {
        assertThat(MembershipProfileValidator.isValidForSignUp(Carrier.SKT, Grade.SKT_VIP)).isTrue();
    }

    @Test
    void signupAcceptsMvnoWithoutGrade() {
        assertThat(MembershipProfileValidator.isValidForSignUp(Carrier.MVNO, null)).isTrue();
    }

    @Test
    void signupRejectsMvnoWithAnyGrade() {
        assertThat(MembershipProfileValidator.isValidForSignUp(Carrier.MVNO, Grade.SKT_VIP)).isFalse();
    }

    @Test
    void signupRejectsIncompletePair() {
        assertThat(MembershipProfileValidator.isValidForSignUp(Carrier.SKT, null)).isFalse();
        assertThat(MembershipProfileValidator.isValidForSignUp(null, Grade.SKT_VIP)).isFalse();
    }

    @Test
    void signupRejectsGradeThatDoesNotBelongToCarrier() {
        assertThat(MembershipProfileValidator.isValidForSignUp(Carrier.KT, Grade.SKT_VIP)).isFalse();
    }

    @Test
    void profileUpdatesStillRequireAValidPair() {
        assertThat(MembershipProfileValidator.isValid(null, null)).isFalse();
    }

    @Test
    void validProfilePairsRemainCarrierScoped() {
        assertThat(MembershipProfileValidator.isValid(Carrier.LGU, Grade.VIP)).isTrue();
        assertThat(MembershipProfileValidator.isValid(Carrier.SKT, Grade.SKT_VIP)).isTrue();
        assertThat(MembershipProfileValidator.isValid(Carrier.KT, Grade.KT_VVIP)).isTrue();
        assertThat(MembershipProfileValidator.isValid(Carrier.SKT, Grade.VIP)).isFalse();
        assertThat(MembershipProfileValidator.isValid(Carrier.KT, Grade.VVIP)).isFalse();
        assertThat(MembershipProfileValidator.isValid(null, Grade.VIP)).isFalse();
        assertThat(MembershipProfileValidator.isValid(Carrier.LGU, null)).isFalse();
    }
}
