package com.itplace.userapi.security.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataMaskerTest {

    @Test
    void masksMiddleDigitsOfPhoneNumber() {
        assertThat(SensitiveDataMasker.maskPhoneNumber("01012345678")).isEqualTo("010****5678");
    }
}
