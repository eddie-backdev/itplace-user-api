package com.itplace.userapi.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.security.CookieUtil;
import com.itplace.userapi.security.auth.local.filter.LoginFilter;
import com.itplace.userapi.security.auth.oauth.handler.OAuth2AuthenticationFailureHandler;
import com.itplace.userapi.security.auth.oauth.handler.OAuth2AuthenticationSuccessHandler;
import com.itplace.userapi.security.auth.oauth.service.CustomOAuth2UserService;
import com.itplace.userapi.security.jwt.JWTFilter;
import com.itplace.userapi.security.jwt.JWTUtil;
import com.itplace.userapi.user.repository.MembershipRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
    private final MembershipRepository membershipRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CookieUtil cookieUtil;
    private final JWTUtil jwtUtil;
    private final JWTFilter jwtFilter;

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

                        configuration.setAllowedMethods(Collections.singletonList("*"));
                        configuration.setAllowCredentials(true);
                        configuration.setAllowedHeaders(Collections.singletonList("*"));
                        configuration.setMaxAge(3600L);

                        configuration.setExposedHeaders(Collections.singletonList("Authorization"));
                        return configuration;
                    }
                })));

        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);

        // 경로별 인가 작업
        http
                .authorizeHttpRequests((auth) -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/users").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/users").authenticated()
                        .requestMatchers(
                                "/api/v1/questions/search",
                                "/api/v1/questions/recommend",
                                "/api/v1/questions/save",
                                "/api/v1/users/changePassword",
                                "/api/v1/users/checkUplusData",
                                "/api/v1/users/linkUplusData",
                                "/api/v1/favorites/**",
                                "/api/v1/gifts/**"
                        ).authenticated()

                        .anyRequest().permitAll());

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
                membershipRepository,
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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
