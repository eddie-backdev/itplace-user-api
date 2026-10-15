package com.itplace.userapi.benefitusage.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.itplace.userapi.common.GlobalExceptionHandler;
import com.itplace.userapi.benefitusage.BenefitUsageCode;
import com.itplace.userapi.benefitusage.dto.request.CreateBenefitUsageRecordRequest;
import com.itplace.userapi.benefitusage.exception.BenefitUsageException;
import com.itplace.userapi.benefitusage.service.BenefitUsageService;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.stream.Stream;
import jakarta.validation.Validation;

class BenefitUsageControllerTest {
    private MockMvc mockMvc;
    private BenefitUsageService service;

    @Test
    void requestValidationAcceptsInclusiveAmountAndMemoLimits() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new CreateBenefitUsageRecordRequest(1L,
                    java.time.LocalDate.of(2026, 9, 1), new java.math.BigDecimal("100000000"), "m".repeat(200));
            org.assertj.core.api.Assertions.assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    @BeforeEach
    void setUp() {
        service = Mockito.mock(BenefitUsageService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new BenefitUsageController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void createRejectsSavingsAboveLimitBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/benefit-usages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benefitId":20,"usedAt":"2026-09-01","savedAmountKrw":100000001}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsMemoOverLimitBeforeCallingService() throws Exception {
        String longMemo = "m".repeat(201);
        mockMvc.perform(post("/api/v1/benefit-usages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"benefitId\":20,\"usedAt\":\"2026-09-01\",\"memo\":\"" + longMemo + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsFractionalSavingsInsteadOfTruncating() throws Exception {
        mockMvc.perform(post("/api/v1/benefit-usages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"benefitId":20,"usedAt":"2026-09-01","savedAmountKrw":1.5}
                                """))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @MethodSource("invalidCreateBodies")
    void createRejectsInvalidRequiredFieldsAndAmountsBeforeService(String body) throws Exception {
        mockMvc.perform(post("/api/v1/benefit-usages")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidCreateBodies() {
        return Stream.of(
                arguments("{\"usedAt\":\"2026-09-01\"}"),
                arguments("{\"benefitId\":0,\"usedAt\":\"2026-09-01\"}"),
                arguments("{\"benefitId\":20}"),
                arguments("{\"benefitId\":20,\"usedAt\":\"not-a-date\"}"),
                arguments("{\"benefitId\":20,\"usedAt\":\"2026-09-01\",\"savedAmountKrw\":-1}"),
                arguments("{\"benefitId\":20,\"usedAt\":\"2026-09-01\",\"savedAmountKrw\":100000001}"),
                arguments("{\"benefitId\":20,\"usedAt\":\"2026-09-01\",\"memo\":\"" + "m".repeat(201) + "\"}"),
                arguments("{broken json")
        );
    }

    @Test
    void getMonthRejectsYearZero() throws Exception {
        PrincipalDetails principal = () -> 7L;
        Mockito.when(service.getMonth(7L, "0000-01"))
                .thenThrow(new BenefitUsageException(BenefitUsageCode.INVALID_MONTH));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
        try {
            mockMvc.perform(get("/api/v1/benefit-usages").param("month", "0000-01"))
                    .andExpect(status().isBadRequest());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
