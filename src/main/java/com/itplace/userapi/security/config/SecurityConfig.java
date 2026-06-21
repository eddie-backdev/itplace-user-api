package com.itplace.userapi.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.LegacyAwarePasswordEncoder;
import com.itplace.userapi.security.auth.local.filter.LoginFilter;
import com.itplace.userapi.security.auth.local.service.CustomUserDetailsService;
import com.itplace.userapi.security.auth.oauth.handler.OAuth2AuthenticationFailureHandler;
import com.itplace.userapi.security.auth.oauth.handler.OAuth2AuthenticationSuccessHandler;
import com.itplace.userapi.security.auth.oauth.service.CustomOAuth2UserService;
import com.itplace.userapi.security.jwt.JWTFilter;
import com.itplace.userapi.security.jwt.JWTUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthenticationConfiguration authenticationConfiguration;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CookieUtil cookieUtil;
    private final JWTUtil jwtUtil;
    private final JWTFilter jwtFilter;

    @Value("${app.cookie.domain:}")
    private String cookieDomain;

    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${app.cookie.same-site:Lax}")
    private String cookieSameSite;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthenticationManager authenticationManager) throws Exception {

        http
                .cors((corsCustomizer -> corsCustomizer.configurationSource(new CorsConfigurationSource() {
                    @Override
                    public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
                        CorsConfiguration configuration = new CorsConfiguration();

                        configuration.setAllowedOriginPatterns(Arrays.asList(
                                "http://localhost:3000", // 로컬 개발 환경
                                "http://localhost:5173", // 로컬 개발 환경
                                "http://localhost:5174", // 로컬 개발 환경
                                "http://localhost:8080", // 로컬 개발 환경
                                "https://itplace.click",
                                "https://www.itplace.click",
                                "https://userapi.itplace.click"
                        ));

                        configuration.setAllowedMethods(Arrays.asList(
                                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
                        ));
                        configuration.setAllowCredentials(true);
                        configuration.setAllowedHeaders(Arrays.asList(
                                "Accept", "Authorization", "Content-Type", "X-XSRF-TOKEN"
                        ));
                        configuration.setMaxAge(3600L);

                        configuration.setExposedHeaders(Arrays.asList("X-XSRF-TOKEN"));
                        return configuration;
                    }
                })));

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(
                                "/api/v1/internal/benefits/**",
                                "/internal/benefits/**"
                        ))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        // 경로별 인가 작업: 공개 API를 명시하고 나머지는 인증 필요로 닫는다.
        http
                .authorizeHttpRequests((auth) -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(publicEndpoints()).permitAll()
                        .requestMatchers(HttpMethod.GET, publicReadEndpoints()).permitAll()
                        .requestMatchers(HttpMethod.POST, publicPostEndpoints()).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(authenticatedEndpoints()).authenticated()
                        .anyRequest().authenticated());

        http
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService))
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler));

        LoginFilter loginFilter = new LoginFilter(
                authenticationManager(authenticationConfiguration),
                jwtUtil,
                redisTemplate,
                objectMapper,
                cookieUtil);
        loginFilter.setFilterProcessesUrl("/api/v1/auth/login");

        http
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtFilter, LoginFilter.class);

        // 세션 설정 : STATELESS
        http
                .sessionManagement((session) -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }

    static String[] publicEndpoints() {
        return new String[]{
                "/hc",
                "/env",
                "/api-docs",
                "/swagger.yml",
                "/swagger-ui/**",
                "/swagger-ui.html",
                "/v3/api-docs/**",
                "/actuator/health",
                "/error",
                "/oauth2/**",
                "/login/oauth2/**"
        };
    }

    static String[] publicReadEndpoints() {
        return new String[]{
                "/api/v1/auth/csrf",
                "/api/v1/benefits",
                "/api/v1/benefits/**",
                "/api/v1/benefit",
                "/api/v1/benefit/**",
                "/api/v1/maps/**",
                "/api/v1/mobile/map/**",
                "/api/v1/partners/search-ranking"
        };
    }

    static String[] publicPostEndpoints() {
        return new String[]{
                "/api/v1/auth/login",
                "/api/v1/auth/reissue",
                "/api/v1/auth/signUp",
                "/api/v1/auth/recaptcha",
                "/api/v1/auth/oauth/kakao",
                "/api/v1/auth/oauth/signUp",
                "/api/v1/auth/oauth/link",
                "/api/v1/verification/email",
                "/api/v1/verification/email/confirm",
                "/api/v1/verification/sms",
                "/api/v1/verification/sms/confirm",
                "/api/v1/users/findPassword",
                "/api/v1/users/findPassword/confirm",
                "/api/v1/users/resetPassword",
                "/api/v1/inquiries",
                "/api/v1/filter",
                "/api/v1/internal/benefits/**",
                "/internal/benefits/**"
        };
    }

    static String[] authenticatedEndpoints() {
        return new String[]{
                "/api/v1/users",
                "/api/v1/users/**",
                "/api/v1/favorites",
                "/api/v1/favorites/**",
                "/api/v1/auth/logout",
                "/api/v1/auth/oauth/result",
                "/api/v1/questions/search",
                "/api/v1/questions/recommend",
                "/api/v1/questions/save",
                "/api/v1/recommendations"
        };
    }

    private CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(builder -> {
            builder.path("/")
                    .secure(cookieSecure)
                    .sameSite(cookieSameSite);
            if (StringUtils.hasText(cookieDomain) && !"localhost".equalsIgnoreCase(cookieDomain)) {
                builder.domain(cookieDomain);
            }
        });
        return repository;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new LegacyAwarePasswordEncoder();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(
            CustomUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(passwordEncoder);
        provider.setUserDetailsService(userDetailsService);
        provider.setUserDetailsPasswordService(userDetailsService);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
