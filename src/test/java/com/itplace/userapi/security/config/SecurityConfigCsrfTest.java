package com.itplace.userapi.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.when;

import com.itplace.userapi.common.ApiResponse;
import com.itplace.userapi.inquiry.controller.InquiryController;
import com.itplace.userapi.inquiry.service.InquiryService;
import com.itplace.userapi.map.dto.MapDirectionsRequest;
import com.itplace.userapi.map.dto.MapDirectionsResponse;
import com.itplace.userapi.map.dto.CoordinateDirectionsRequest;
import com.itplace.userapi.map.dto.LocationSearchRequest;
import com.itplace.userapi.map.dto.LocationSearchResponse;
import com.itplace.userapi.map.controller.CoordinateDirectionsController;
import com.itplace.userapi.map.controller.LocationSearchController;
import com.itplace.userapi.map.service.LocationSearchService;
import com.itplace.userapi.map.service.MapDirectionsRateLimiter;
import com.itplace.userapi.map.service.MapDirectionsService;
import com.itplace.userapi.map.controller.MapDirectionsController;
import com.itplace.userapi.map.controller.MapPartnerViewController;
import com.itplace.userapi.map.service.MapPartnerViewService;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.security.auth.local.controller.AuthController;
import com.itplace.userapi.security.auth.local.controller.AuthController.CsrfTokenResponse;
import com.itplace.userapi.security.auth.local.service.AuthService;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.AntPathMatcher;
import java.util.Arrays;

class SecurityConfigCsrfTest {

    private InquiryService inquiryService;
    private AuthController authController;
    private MockMvc mockMvc;
    private MockMvc mapDirectionsMockMvc;
    private MockMvc coordinateDirectionsMockMvc;
    private MockMvc locationSearchMockMvc;
    private MockMvc mapPartnerViewMockMvc;
    private MapDirectionsRateLimiter directionsRateLimiter;
    private MapPartnerViewService mapPartnerViewService;

    @BeforeEach
    void setUp() {
        inquiryService = mock(InquiryService.class);
        authController = new AuthController(mock(AuthService.class), mock(CookieUtil.class));
        mockMvc = MockMvcBuilders.standaloneSetup(new InquiryController(inquiryService))
                .addFilters(new CsrfFilter(new HttpSessionCsrfTokenRepository()))
                .build();
        MapDirectionsService directionsService = mock(MapDirectionsService.class);
        when(directionsService.directions(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any(MapDirectionsRequest.class)))
                .thenReturn(new MapDirectionsResponse(MapDirectionsRequest.Mode.WALK,
                        new MapDirectionsResponse.Coordinate(37.5, 127.0),
                        new MapDirectionsResponse.Destination(42L, "Store", 37.51, 127.01), java.util.List.of()));
        CsrfFilter directionsCsrfFilter = new CsrfFilter(new HttpSessionCsrfTokenRepository());
        directionsCsrfFilter.setRequireCsrfProtectionMatcher(csrfProtectionMatcher());
        directionsRateLimiter = mock(MapDirectionsRateLimiter.class);
        when(directionsRateLimiter.check()).thenReturn(true);
        when(directionsRateLimiter.checkLocationSearch()).thenReturn(true);
        mapDirectionsMockMvc = MockMvcBuilders.standaloneSetup(new MapDirectionsController(
                        directionsService, directionsRateLimiter))
                .addFilters(directionsCsrfFilter)
                .build();

        when(directionsService.directions(org.mockito.ArgumentMatchers.any(CoordinateDirectionsRequest.class)))
                .thenReturn(new MapDirectionsResponse(MapDirectionsRequest.Mode.WALK,
                        new MapDirectionsResponse.Coordinate(37.5, 127.0),
                        new MapDirectionsResponse.Destination(null, "도착지", 37.51, 127.01), java.util.List.of()));
        CsrfFilter coordinateCsrfFilter = new CsrfFilter(new HttpSessionCsrfTokenRepository());
        coordinateCsrfFilter.setRequireCsrfProtectionMatcher(csrfProtectionMatcher());
        coordinateDirectionsMockMvc = MockMvcBuilders.standaloneSetup(new CoordinateDirectionsController(
                        directionsService, directionsRateLimiter))
                .addFilters(coordinateCsrfFilter)
                .build();

        LocationSearchService locationSearchService = mock(LocationSearchService.class);
        when(locationSearchService.search(org.mockito.ArgumentMatchers.any(LocationSearchRequest.class)))
                .thenReturn(new LocationSearchResponse(java.util.List.of(), false));
        CsrfFilter locationSearchCsrfFilter = new CsrfFilter(new HttpSessionCsrfTokenRepository());
        locationSearchCsrfFilter.setRequireCsrfProtectionMatcher(csrfProtectionMatcher());
        locationSearchMockMvc = MockMvcBuilders.standaloneSetup(new LocationSearchController(
                        locationSearchService, directionsRateLimiter))
                .addFilters(locationSearchCsrfFilter)
                .build();

        mapPartnerViewService = mock(MapPartnerViewService.class);
        when(mapPartnerViewService.hashAnonymousSessionId("d9428888-122b-4c94-b775-701c9c3a0001"))
                .thenReturn("a".repeat(64));
        CsrfFilter mapPartnerViewCsrfFilter = new CsrfFilter(new HttpSessionCsrfTokenRepository());
        mapPartnerViewCsrfFilter.setRequireCsrfProtectionMatcher(csrfProtectionMatcher());
        when(directionsRateLimiter.checkPartnerView()).thenReturn(true);
        mapPartnerViewMockMvc = MockMvcBuilders.standaloneSetup(new MapPartnerViewController(
                        mapPartnerViewService, directionsRateLimiter))
                .addFilters(mapPartnerViewCsrfFilter)
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
    void guestMapViewPostNeedsNoCsrfToken() throws Exception {
        mapPartnerViewMockMvc.perform(post("/api/v1/maps/stores/42/view")
                        .header("X-Map-View-Session", "d9428888-122b-4c94-b775-701c9c3a0001"))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedMapViewUsesPrincipalAndDoesNotRequireGuestHeader() throws Exception {
        PrincipalDetails principal = () -> 123L;
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.TestingAuthenticationToken(principal, "n/a"));
        try {
            mapPartnerViewMockMvc.perform(post("/api/v1/maps/stores/42/view")
                            .header("X-Map-View-Session", "not-a-uuid"))
                    .andExpect(status().isOk());
            org.mockito.Mockito.verify(mapPartnerViewService).recordStoreView(42L, 123L, null);
            org.mockito.Mockito.verify(mapPartnerViewService,
                    org.mockito.Mockito.never()).hashAnonymousSessionId(org.mockito.ArgumentMatchers.any());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            if (previous != null) {
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(previous);
            }
        }
    }

    @Test
    void publicDirectionsPostWorksWithoutCsrfToken() throws Exception {
        mapDirectionsMockMvc.perform(post("/api/v1/maps/stores/42/directions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"WALK","origin":{"lat":37.5,"lng":127.0}}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void sharedRateLimitReturnsRetryAfterWithoutCsrfToken() throws Exception {
        when(directionsRateLimiter.check()).thenReturn(false);

        mapDirectionsMockMvc.perform(post("/api/v1/maps/stores/42/directions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"WALK","origin":{"lat":37.5,"lng":127.0}}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(HttpHeaders.RETRY_AFTER, "60"));
    }

    @Test
    void genericDirectionsPostWorksWithoutCsrfAndSerializesExplicitNullStoreId() throws Exception {
        var response = coordinateDirectionsMockMvc.perform(post("/api/v1/maps/directions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"WALK","origin":{"lat":37.5,"lng":127.0},
                                 "destination":{"lat":37.51,"lng":127.01}}
                                """))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString());
        assertThat(json.path("data").path("destination").has("storeId")).isTrue();
        assertThat(json.path("data").path("destination").path("storeId").isNull()).isTrue();
    }

    @Test
    void locationSearchPostWorksWithoutCsrfAndKeepsNameOptional() throws Exception {
        locationSearchMockMvc.perform(post("/api/v1/maps/locations/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"서울역","center":{"lat":37.5,"lng":127.0}}
                                """))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void locationSearchRateLimitReturnsRetryAfterWithoutCsrf() throws Exception {
        when(directionsRateLimiter.checkLocationSearch()).thenReturn(false);
        locationSearchMockMvc.perform(post("/api/v1/maps/locations/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"서울역\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string(HttpHeaders.RETRY_AFTER, "60"));
    }

    @Test
    void locationSearchRejectsQueryShorterThanTwoTrimmedCharacters() throws Exception {
        locationSearchMockMvc.perform(post("/api/v1/maps/locations/search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"  a  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void genericDirectionsRejectsDestinationNamesOverOneHundredCharacters() throws Exception {
        String name = "x".repeat(101);
        coordinateDirectionsMockMvc.perform(post("/api/v1/maps/directions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"WALK","origin":{"lat":37.5,"lng":127.0},
                                 "destination":{"lat":37.51,"lng":127.01,"name":"%s"}}
                                """.formatted(name)))
                .andExpect(status().isBadRequest());
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

    private RequestMatcher csrfProtectionMatcher() {
        AntPathMatcher pathMatcher = new AntPathMatcher();
        return request -> {
            String method = request.getMethod();
            boolean unsafe = "POST".equals(method) || "PUT".equals(method)
                    || "PATCH".equals(method) || "DELETE".equals(method);
            boolean ignored = Arrays.stream(SecurityConfig.csrfIgnoredEndpoints())
                    .anyMatch(pattern -> pathMatcher.match(pattern, request.getRequestURI()));
            return unsafe && !ignored;
        };
    }
}
