package com.itplace.userapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LegacyAwarePasswordEncoderTest {

    private final LegacyAwarePasswordEncoder passwordEncoder = new LegacyAwarePasswordEncoder();

    @Test
    void matchesLegacyPlainTextPassword() {
        assertThat(passwordEncoder.matches("1234", "1234")).isTrue();
        assertThat(passwordEncoder.matches("wrong", "1234")).isFalse();
    }

    @Test
    void matchesBcryptPassword() {
        String encodedPassword = passwordEncoder.encode("1234");

        assertThat(encodedPassword).startsWith("$2");
        assertThat(passwordEncoder.matches("1234", encodedPassword)).isTrue();
        assertThat(passwordEncoder.matches("wrong", encodedPassword)).isFalse();
    }

    @Test
    void keepsBcryptForNewPasswordsAndMarksLegacyRowsForUpgrade() {
        String encodedPassword = passwordEncoder.encode("1234");

        assertThat(passwordEncoder.upgradeEncoding("1234")).isTrue();
        assertThat(passwordEncoder.upgradeEncoding(encodedPassword)).isFalse();
    }
}
