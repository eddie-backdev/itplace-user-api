package com.itplace.userapi.security.jwt;

import com.itplace.userapi.user.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JWTUtil {

    private final SecretKey secretKey;
    @Getter
    private final Long accessTokenValidityInMS;
    @Getter
    private final Long refreshTokenValidityInMS; // 변경점 1: Refresh Token 만료 시간 필드 추가

    public JWTUtil(
            @Value("${spring.jwt.secret}") String secret,
            @Value("${spring.jwt.access-token-validity-in-seconds}") long accessTokenValidity,
            @Value("${spring.jwt.refresh-token-validity-in-seconds}") long refreshTokenValidity
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenValidityInMS = accessTokenValidity * 1000;
        this.refreshTokenValidityInMS = refreshTokenValidity * 1000;
    }

    public Long getUserId(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)
                .getPayload().get("userId", Long.class);
    }

    public Role getRole(String token) {
        String roleString = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload().get("role", String.class);
        return Role.fromKey(roleString);
    }

    public String getCategory(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token)
                .getPayload().get("category", String.class);
    }

    public Boolean isExpired(String token) {
        return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload().getExpiration().before(new Date());
    }

    public String createJwt(Long userId, String role, String category) {
        long now = System.currentTimeMillis();
        long validity = JWTConstants.CATEGORY_ACCESS.equals(category) ? accessTokenValidityInMS : refreshTokenValidityInMS;

        return Jwts.builder()
                .claim(JWTConstants.CLAIM_CATEGORY, category)
                .claim(JWTConstants.CLAIM_USER_ID, userId)
                .claim(JWTConstants.CLAIM_ROLE, role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + validity))
                .signWith(secretKey)
                .compact();
    }

    public String createTempJwt(String provider, String providerId) {
        long now = System.currentTimeMillis();
        long validity = 10 * 60 * 1000L; // 10분 유효

        return Jwts.builder()
                .claim(JWTConstants.CLAIM_CATEGORY, "temp")
                .claim("provider", provider)
                .claim("providerId", providerId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + validity))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 토큰을 파싱하여 모든 클레임(정보)을 반환합니다.
     * 이 과정에서 토큰의 유효성(서명, 만료 시간 등)이 검증됩니다.
     *
     * @param token 검증할 JWT
     * @return 토큰의 클레임
     */
    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}