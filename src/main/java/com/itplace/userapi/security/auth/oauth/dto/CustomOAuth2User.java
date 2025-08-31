package com.itplace.userapi.security.auth.oauth.dto;

import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.user.entity.User;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

@RequiredArgsConstructor
public class CustomOAuth2User implements OAuth2User, PrincipalDetails {

    private final OAuth2User oAuth2User;
    @Getter
    private final String provider;
    @Getter
    private final String providerId;
    @Getter
    private final User user;

    @Override
    public Long getUserId() {
        if (user == null) {
            return null;
        }
        return user.getId();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return oAuth2User.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 기존 사용자의 경우, 실제 권한을 부여합니다.
        if (user != null) {
            return user.getAuthorities();
        }
        // 신규 사용자는 기본 권한을 부여할 수 있습니다.
        return new ArrayList<>();
    }

    @Override
    public String getName() {
        return oAuth2User.getName();
    }

    // 신규 사용자인지 여부를 판단하는 헬퍼 메소드
    public boolean isNewUser() {
        return this.user == null;
    }
}
