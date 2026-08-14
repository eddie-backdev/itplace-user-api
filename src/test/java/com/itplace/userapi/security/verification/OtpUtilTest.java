package com.itplace.userapi.security.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
class OtpUtilTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Test
    void validateEmailOtpReturnsTrueOnlyWhenAtomicRedisValidationConsumesCode() {
        when(redisTemplate.execute(
                org.mockito.ArgumentMatchers.<RedisScript<Long>>any(),
                anyList(),
                any(),
                any(),
                any()
        )).thenReturn(1L, 0L);
        OtpUtil otpUtil = new OtpUtil(redisTemplate);

        assertThat(otpUtil.validateEmailOtp("hong@example.com", "123456")).isTrue();
        assertThat(otpUtil.validateEmailOtp("hong@example.com", "123456")).isFalse();
    }
}
