package com.itplace.userapi.favorite.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itplace.userapi.common.GlobalExceptionHandler;
import com.itplace.userapi.favorite.service.FavoriteService;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FavoriteControllerTest {
    private FavoriteService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(FavoriteService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FavoriteController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @MethodSource("invalidRemovalBodies")
    void removeRejectsMissingOrInvalidIdsBeforeCallingService(String body) throws Exception {
        mvc.perform(delete("/api/v1/favorites")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    static Stream<String> invalidRemovalBodies() {
        return Stream.of("{}", "{\"benefitIds\":null}", "{\"benefitIds\":[null]}",
                "{\"benefitIds\":[0]}", "{\"benefitIds\":[-1]}");
    }

    @org.junit.jupiter.api.Test
    void emptyRemovalListRemainsAValidNoOp() throws Exception {
        PrincipalDetails principal = () -> 7L;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        try {
            mvc.perform(delete("/api/v1/favorites")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"benefitIds\":[]}"))
                    .andExpect(status().isOk());
            verify(service).removeFavorites(7L, List.of());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
