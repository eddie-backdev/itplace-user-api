package com.itplace.userapi.benefit.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse.BenefitPreview;
import com.itplace.userapi.benefit.dto.response.PartnerBenefitListResponse.PreviewTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
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
    void getPartnersReturnsPublicPreviewContractWithMatchingFilters() throws Exception {
        when(service.getPartners(eq(MainCategory.BASIC_BENEFIT), eq(null), eq(UsageType.OFFLINE),
                eq(null), eq(null), eq(List.of(Carrier.SKT)), eq(Grade.SKT_GOLD), any(Pageable.class)))
                .thenReturn(PageResult.<PartnerBenefitListResponse>builder()
                        .content(List.of(PartnerBenefitListResponse.builder()
                                .partnerId(10L).partnerName("GS25").carriers(List.of(Carrier.SKT))
                                .previews(List.of(new BenefitPreview(Carrier.SKT, 20L, "GS25 할인",
                                        "제한없음", UsageType.BOTH,
                                        List.of(new PreviewTierBenefit(Grade.SKT_GOLD, "1천원당 100원 할인", true)))))
                                .build()))
                        .totalElements(1).totalPages(1).currentPage(0).hasNext(false).build());

        mockMvc.perform(get("/api/v1/benefits/partners")
                        .param("mainCategory", "BASIC_BENEFIT").param("filter", "OFFLINE")
                        .param("carrier", "SKT").param("grade", "SKT_GOLD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].previews[0].carrier").value("SKT"))
                .andExpect(jsonPath("$.data.content[0].previews[0].benefitId").value(20))
                .andExpect(jsonPath("$.data.content[0].previews[0].benefitName").value("GS25 할인"))
                .andExpect(jsonPath("$.data.content[0].previews[0].benefitLimit").value("제한없음"))
                .andExpect(jsonPath("$.data.content[0].previews[0].usageType").value("BOTH"))
                .andExpect(jsonPath("$.data.content[0].previews[0].tierBenefits[0].grade").value("SKT_GOLD"))
                .andExpect(jsonPath("$.data.content[0].previews[0].tierBenefits[0].context").value("1천원당 100원 할인"))
                .andExpect(jsonPath("$.data.content[0].previews[0].tierBenefits[0].isAll").value(true))
                .andExpect(jsonPath("$.data.content[0].previews[0].isFavorite").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].previews[0].favoriteCount").doesNotExist());
    }

    @Test
    void unfilteredDetailIncludesAllCategories() throws Exception {
        mockMvc.perform(get("/api/v1/benefits/partners/10")).andExpect(status().isOk());
        verify(service).getPartnerDetail(10L, null, null, null, null);
    }

    @Test
    void detailKeepsExplicitCategoryFilter() throws Exception {
        mockMvc.perform(get("/api/v1/benefits/partners/10").param("mainCategory", "BASIC_BENEFIT"))
                .andExpect(status().isOk());
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
