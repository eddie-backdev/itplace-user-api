package com.itplace.userapi.security.jwt;

import com.itplace.userapi.security.auth.local.dto.CustomUserDetails;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
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

        // 2) 토큰이 없거나 만료되었으면 다음 필터로 패스
        if (token == null) {
            log.info("쿠키에 토큰이 없습니다.");
            filterChain.doFilter(request, response);
            return;
        }
        if (jwtUtil.isExpired(token)) {
            log.info("토큰이 만료되었습니다.");
            filterChain.doFilter(request, response);
            return;
        }

        // 3) 카테고리 확인 (access 토큰 여부)
        String category = jwtUtil.getCategory(token);
        if (!JWTConstants.CATEGORY_ACCESS.equals(category)) {
            log.info("Access 토큰이 아닙니다.");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        // 4) 토큰에서 사용자 정보 꺼내서 Authentication 설정
        Long userId = jwtUtil.getUserId(token);
        Role role = jwtUtil.getRole(token);

        User user = User.builder()
                .id(userId)
                .password(UUID.randomUUID().toString())  // 실제 패스워드는 사용되지 않음
                .role(role)
                .build();

        CustomUserDetails principal = new CustomUserDetails(user);
        Authentication authToken =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()
                );
        SecurityContextHolder.getContext().setAuthentication(authToken);

        filterChain.doFilter(request, response);
    }
}
