package com.itplace.userapi.benefit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.benefit.dto.response.BenefitListResponse;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.service.BenefitService;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
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

class BenefitControllerTest {

    private MockMvc mockMvc;

    @Mock
    private BenefitService benefitService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        BenefitController controller = new BenefitController(benefitService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    void getBenefits_supportsLegacySingularPath() throws Exception {
        when(benefitService.getBenefitList(eq(MainCategory.BASIC_BENEFIT), eq(null), eq(null), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(PageResult.<BenefitListResponse>builder()
                        .content(List.of())
                        .totalElements(0)
                        .totalPages(0)
                        .currentPage(0)
                        .hasNext(false)
                        .build());

        mockMvc.perform(get("/api/v1/benefit")
                        .param("mainCategory", "BASIC_BENEFIT"))
                .andExpect(status().isOk());

        verify(benefitService).getBenefitList(eq(MainCategory.BASIC_BENEFIT), eq(null), eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    void getBenefitsPassesExplicitCarrierFilter() throws Exception {
        when(benefitService.getBenefitList(eq(MainCategory.BASIC_BENEFIT), eq(null), eq(null), eq(null), eq(Carrier.SKT), eq(null), any(Pageable.class)))
                .thenReturn(PageResult.<BenefitListResponse>builder()
                        .content(List.of())
                        .totalElements(0)
                        .totalPages(0)
                        .currentPage(0)
                        .hasNext(false)
                        .build());

        mockMvc.perform(get("/api/v1/benefits")
                        .param("mainCategory", "BASIC_BENEFIT")
                        .param("carrier", "SKT"))
                .andExpect(status().isOk());

        verify(benefitService).getBenefitList(eq(MainCategory.BASIC_BENEFIT), eq(null), eq(null), eq(null), eq(Carrier.SKT), eq(null), any(Pageable.class));
    }

}
