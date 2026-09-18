package com.itplace.userapi.benefit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.service.PartnerBenefitBrowseService;
import com.itplace.userapi.common.GlobalExceptionHandler;
import com.itplace.userapi.common.PageResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PartnerBenefitControllerTest {

    private MockMvc mockMvc;

    @Mock
    private PartnerBenefitBrowseService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        mockMvc = MockMvcBuilders.standaloneSetup(new PartnerBenefitController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                .build();
    }

    @Test
    void getPartners_passesCarrierFiltersToPartnerBrowseService() throws Exception {
        when(service.getPartners(
                eq(MainCategory.BASIC_BENEFIT), eq(null), eq(null), eq(null), eq(null),
                eq(List.of(Carrier.SKT, Carrier.KT)), eq(null), any(Pageable.class)
        )).thenReturn(PageResult.<PartnerBenefitListResponse>builder()
                .content(List.of())
                .totalElements(0)
                .totalPages(0)
                .currentPage(0)
                .hasNext(false)
                .build());

        mockMvc.perform(get("/api/v1/benefits/partners")
                        .param("mainCategory", "BASIC_BENEFIT")
                        .param("carriers", "SKT,KT"))
                .andExpect(status().isOk());

        verify(service).getPartners(
                eq(MainCategory.BASIC_BENEFIT), eq(null), eq(null), eq(null), eq(null),
                eq(List.of(Carrier.SKT, Carrier.KT)), eq(null), any(Pageable.class)
        );
    }

    @Test
    void getPartnersPassesMembershipGradeWithoutRequiringLogin() throws Exception {
        mockMvc.perform(get("/api/v1/benefits/partners")
                        .param("carrier", "SKT").param("grade", "SKT_GOLD"))
                .andExpect(status().isOk());
        verify(service).getPartners(eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(List.of(Carrier.SKT)), eq(Grade.SKT_GOLD), any(Pageable.class));
    }

    @Test
    void unfilteredDetailKeepsDefaultCategory() throws Exception {
        mockMvc.perform(get("/api/v1/benefits/partners/10")).andExpect(status().isOk());
        verify(service).getPartnerDetail(10L, MainCategory.BASIC_BENEFIT, null, null, null);
    }

    @Test
    void carrierDetailCanCompareAllGradesIncludingVipKokCategory() throws Exception {
        mockMvc.perform(get("/api/v1/benefits/partners/10").param("carrier", "LGU"))
                .andExpect(status().isOk());
        verify(service).getPartnerDetail(10L, null, null, Carrier.LGU, null);
    }

    @Test
    void getPartnersRejectsOversizedPage() throws Exception {
        mockMvc.perform(get("/api/v1/benefits/partners")
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }
}
