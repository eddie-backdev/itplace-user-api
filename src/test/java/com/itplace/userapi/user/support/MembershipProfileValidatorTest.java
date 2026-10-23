package com.itplace.userapi.user.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.reference.ReferenceDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MembershipProfileValidatorTest {
    @Mock ReferenceDataService references;
    MembershipProfileValidator validator;

    @BeforeEach void setUp() {
        validator = new MembershipProfileValidator(references);
        lenient().when(references.isSelectableProfile(Carrier.LGU, Grade.VIP)).thenReturn(true);
        lenient().when(references.isSelectableProfile(Carrier.SKT, Grade.SKT_VIP)).thenReturn(true);
        lenient().when(references.isSelectableProfile(Carrier.KT, Grade.KT_VVIP)).thenReturn(true);
        lenient().when(references.isSelectableProfile(Carrier.SKT, Grade.of("SKT_PLATINUM"))).thenReturn(true);
    }

    @Test void signupAcceptsBothFieldsOmitted() {
        assertThat(validator.isValidForSignUp(null, null)).isTrue();
    }

    @Test void signupAcceptsValidCarrierAndGradePair() {
        assertThat(validator.isValidForSignUp(Carrier.SKT, Grade.SKT_VIP)).isTrue();
    }

    @Test void signupAcceptsMvnoWithoutGrade() {
        assertThat(validator.isValidForSignUp(Carrier.MVNO, null)).isTrue();
    }

    @Test void signupRejectsMvnoWithAnyGrade() {
        assertThat(validator.isValidForSignUp(Carrier.MVNO, Grade.SKT_VIP)).isFalse();
    }

    @Test void signupRejectsIncompletePair() {
        assertThat(validator.isValidForSignUp(Carrier.SKT, null)).isFalse();
        assertThat(validator.isValidForSignUp(null, Grade.SKT_VIP)).isFalse();
    }

    @Test void signupRejectsGradeThatDoesNotBelongToCarrier() {
        assertThat(validator.isValidForSignUp(Carrier.KT, Grade.SKT_VIP)).isFalse();
        assertThat(validator.isValidForSignUp(Carrier.LGU, Grade.DIAMOND)).isFalse();
    }

    @Test void profileUpdatesStillRequireAValidPair() {
        assertThat(validator.isValid(null, null)).isFalse();
        assertThat(validator.isValid(null, Grade.VIP)).isFalse();
        assertThat(validator.isValid(Carrier.LGU, null)).isFalse();
    }

    @Test void databaseAddedGradeNeedsNoCodeChangeAndStillRejectsWrongCarrier() {
        Grade novel = Grade.of("SKT_PLATINUM");
        assertThat(validator.isValidForSignUp(Carrier.SKT, novel)).isTrue();
        assertThat(validator.isValidForSignUp(Carrier.KT, novel)).isFalse();
    }

    @Test void validProfilePairsRemainCarrierScoped() {
        assertThat(validator.isValid(Carrier.LGU, Grade.VIP)).isTrue();
        assertThat(validator.isValid(Carrier.SKT, Grade.SKT_VIP)).isTrue();
        assertThat(validator.isValid(Carrier.KT, Grade.KT_VVIP)).isTrue();
        assertThat(validator.isValid(Carrier.SKT, Grade.VIP)).isFalse();
        assertThat(validator.isValid(Carrier.KT, Grade.VVIP)).isFalse();
        assertThat(validator.isValid(Carrier.LGU, Grade.DIAMOND)).isFalse();
    }
}
