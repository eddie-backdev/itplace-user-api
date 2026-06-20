package com.itplace.userapi.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.inquiry.controller.InquiryController;
import com.itplace.userapi.inquiry.service.InquiryService;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.local.controller.AuthController;
import com.itplace.userapi.security.auth.local.controller.AuthController.CsrfTokenResponse;
import com.itplace.userapi.security.auth.local.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SecurityConfigCsrfTest {

    private InquiryService inquiryService;
    private AuthController authController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        inquiryService = mock(InquiryService.class);
        authController = new AuthController(mock(AuthService.class), mock(CookieUtil.class));
        mockMvc = MockMvcBuilders.standaloneSetup(new InquiryController(inquiryService))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
    }

    @Test
    void unsafeRequestRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/inquiries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validInquiryJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfEndpointReturnsHeaderNameAndToken() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        DefaultCsrfToken csrfToken = new DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", "csrf-token");

        ApiResponse<CsrfTokenResponse> body = authController.csrf(csrfToken, response).getBody();

        assertThat(response.getHeader("X-XSRF-TOKEN")).isEqualTo("csrf-token");
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(SecurityCode.CSRF_TOKEN_ISSUED.getCode());
        assertThat(body.getData().headerName()).isEqualTo("X-XSRF-TOKEN");
        assertThat(body.getData().token()).isEqualTo("csrf-token");
    }

    private String validInquiryJson() {
        return """
                {
                  "category": "AUTH",
                  "title": "CSRF test",
                  "content": "CSRF protected inquiry"
                }
                """;
    }
}
