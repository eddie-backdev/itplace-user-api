package com.itplace.userapi.security.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** 로그인 진행 중 인증 요청만 공유한다. 로그인 후 API 인증은 기존 JWT STATELESS 정책을 쓴다. */
@Configuration
@EnableRedisHttpSession(redisNamespace = "itplace:user:oauth2", maxInactiveIntervalInSeconds = 600)
public class OAuth2SessionConfig {
    @Bean
    public DefaultCookieSerializer cookieSerializer(@Value("${app.cookie.secure:true}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("ITPLACE_OAUTH_SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        // Kakao에서 돌아오는 최상위 GET callback에 세션 쿠키를 전달한다.
        serializer.setSameSite("Lax");
        return serializer;
    }
}
