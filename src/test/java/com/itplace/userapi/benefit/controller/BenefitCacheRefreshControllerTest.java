package com.itplace.userapi.benefit.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.itplace.userapi.benefit.service.BenefitCacheRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BenefitCacheRefreshControllerTest {
    @Test
    void requiresConfiguredInternalKeyBeforeProcessingAnyChanges() throws Exception {
        var service = mock(BenefitCacheRefreshService.class);
        var controller = new BenefitCacheRefreshController(service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        ReflectionTestUtils.setField(controller, "expectedApiKey", "internal-test-key");
        mvc.perform(post("/internal/benefits/cache/refresh")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/benefits/cache/refresh").header("X-Internal-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        when(service.refreshPending()).thenReturn(2);
        mvc.perform(post("/api/v1/internal/benefits/cache/refresh").header("X-Internal-Api-Key", "internal-test-key"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refreshedPartners").value(2));
        ReflectionTestUtils.setField(controller, "expectedApiKey", "");
        mvc.perform(post("/internal/benefits/cache/refresh").header("X-Internal-Api-Key", "internal-test-key"))
                .andExpect(status().isUnauthorized());
        verify(service, times(1)).refreshPending();
    }
}
