package com.itplace.userapi.security.auth.oauth.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KakaoLoginResult {
    // 기존 회원이면 true, 신규 회원이면 false
    private final boolean isExistingUser;

    // 기존 회원일 경우, 즉시 로그인을 위한 최종 토큰 정보
    private final OAuthResult authResult;

    // 신규 회원일 경우, 다음 단계를 위한 임시 토큰
    private final String tempToken;

    // 신규 회원 추가 정보 입력 화면에 미리 채울 카카오 검증 정보
    private final String email;
    private final String nickname;
}