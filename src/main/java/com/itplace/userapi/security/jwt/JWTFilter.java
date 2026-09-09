package com.itplace.userapi.security.jwt;

import com.itplace.userapi.security.auth.local.dto.CustomUserDetails;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class JWTFilter extends OncePerRequestFilter {

    private final JWTUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String token = null;

        // 1) 쿠키에서 access 토큰 찾기
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (JWTConstants.CATEGORY_ACCESS.equals(cookie.getName())) {
                    token = cookie.getValue();
                    log.debug("쿠키에서 토큰을 가져왔습니다.");
                    break;
                }
            }
        }

        // 2) 토큰이 없으면 다음 필터로 패스
        if (token == null) {
            // 공개 API에서는 인증 쿠키가 없는 요청이 정상 흐름이므로 요청마다 INFO 로그를 남기지 않는다.
            log.debug("쿠키에 토큰이 없습니다.");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtUtil.getClaims(token);
            if (!JWTConstants.CATEGORY_ACCESS.equals(claims.get(JWTConstants.CLAIM_CATEGORY, String.class))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            Long userId = claims.get(JWTConstants.CLAIM_USER_ID, Long.class);
            Role role = Role.fromKey(claims.get(JWTConstants.CLAIM_ROLE, String.class));
            if (userId == null || claims.getExpiration() == null) {
                throw new JwtException("필수 access 클레임 누락");
            }

            User user = User.builder()
                    .id(userId)
                    .role(role)
                    .build();

            CustomUserDetails principal = new CustomUserDetails(user, "");
            Authentication authToken =
                    new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities()
                    );
            SecurityContextHolder.getContext().setAuthentication(authToken);

        } catch (JwtException | IllegalArgumentException e) {
            log.warn("유효하지 않은 토큰: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }
}
