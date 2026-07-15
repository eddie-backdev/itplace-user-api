package com.itplace.userapi.benefit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.service.PartnerBenefitBrowseService;
import com.itplace.userapi.common.PageResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Pageable;
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
                .build();
    }

    @Test
    void getPartners_passesCarrierFiltersToPartnerBrowseService() throws Exception {
        when(service.getPartners(
                eq(MainCategory.BASIC_BENEFIT), eq(null), eq(null), eq(null), eq(null),
                eq(List.of(Carrier.SKT, Carrier.KT)), any(Pageable.class)
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
                eq(List.of(Carrier.SKT, Carrier.KT)), any(Pageable.class)
        );
    }
}
