package com.itplace.userapi.security.auth.oauth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.auth.local.dto.response.LoginResponse;
import com.itplace.userapi.security.auth.oauth.dto.response.KakaoLoginResult;
import com.itplace.userapi.security.auth.oauth.dto.response.OAuthResult;
import com.itplace.userapi.security.auth.oauth.service.OAuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OAuthControllerTest {

    private MockMvc mockMvc;

    @Mock
    private OAuthService oAuthService;

    @Mock
    private CookieUtil cookieUtil;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        OAuthController controller = new OAuthController(oAuthService, cookieUtil);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void kakaoLoginSetsSessionCookiesForExistingUser() throws Exception {
        LoginResponse loginResponse = LoginResponse.builder()
                .nickname("홍길동")
                .carrier(Carrier.LGU)
                .membershipGradeCode(Grade.VIP)
                .membershipGrade(Grade.VIP)
                .membershipVerified(true)
                .build();
        OAuthResult authResult = OAuthResult.builder()
                .accessToken("access-token")
                .refreshToken("refresh-token")
                .loginResponse(loginResponse)
                .build();
        when(oAuthService.processKakaoLogin(any()))
                .thenReturn(KakaoLoginResult.builder().isExistingUser(true).authResult(authResult).build());

        mockMvc.perform(post("/api/v1/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "code", "auth-code",
                                "redirectUri", "itplace://oauth/callback/kakao"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("홍길동"));

        verify(cookieUtil).setTokensToCookie(any(HttpServletResponse.class), eq("access-token"), eq("refresh-token"));
    }

    @Test
    void kakaoLoginSetsTempCookieForNewUser() throws Exception {
        when(oAuthService.processKakaoLogin(any()))
                .thenReturn(KakaoLoginResult.builder()
                        .isExistingUser(false)
                        .tempToken("temp-token")
                        .email("kakao@example.com")
                        .nickname("카카오닉")
                        .build());

        mockMvc.perform(post("/api/v1/auth/oauth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("code", "auth-code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PRE_AUTHENTICATION_SUCCESS"))
                .andExpect(jsonPath("$.data.email").value("kakao@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("카카오닉"));

        verify(cookieUtil).setTempTokenCookie(any(HttpServletResponse.class), eq("temp-token"), eq(TimeUnit.MINUTES.toSeconds(10)));
    }

    @Test
    void linkVerifiesLocalPasswordAndSetsSessionCookies() throws Exception {
        LoginResponse loginResponse = LoginResponse.builder()
                .nickname("기존회원")
                .carrier(Carrier.KT)
                .membershipGradeCode(Grade.KT_GOLD)
                .membershipGrade(Grade.KT_GOLD)
                .membershipVerified(false)
                .build();
        OAuthResult authResult = OAuthResult.builder()
                .accessToken("access-token")
                .refreshToken("refresh-token")
                .loginResponse(loginResponse)
                .build();

        when(oAuthService.linkOAuthAccount(any(), any())).thenReturn(authResult);

        mockMvc.perform(post("/api/v1/auth/oauth/link")
                        .cookie(new Cookie("tempToken", "temp-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "email", "owner@example.com",
                                "password", "local-password"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("LOGIN_SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("기존회원"));

        verify(oAuthService).linkOAuthAccount(eq("temp-token"), any());
        verify(cookieUtil).setTokensToCookie(any(HttpServletResponse.class), eq("access-token"), eq("refresh-token"));
    }
}
