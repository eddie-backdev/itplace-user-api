package com.itplace.userapi.security.auth.oauth.service;

import com.itplace.userapi.security.auth.oauth.dto.CustomOAuth2User;
import com.itplace.userapi.user.entity.SocialAccount;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.SocialAccountRepository;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;

    @Override
    @Transactional(readOnly = true)
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId(); // "kakao"
        Map<String, Object> attributes = oAuth2User.getAttributes();
        String providerId = String.valueOf(attributes.get("id"));

        log.info("OAuth2 User Attributes: {}", attributes);

        // provider와 providerId로 SocialAccount를 찾습니다.
        Optional<SocialAccount> socialAccountOpt = socialAccountRepository.findByProviderAndProviderId(provider, providerId);

        User user;
        if (socialAccountOpt.isPresent()) {
            // 이미 가입된 경우, 기존 User 정보를 가져옵니다.
            user = socialAccountOpt.get().getUser();
            log.info("기존 소셜 연동 유저: {}", user.getEmail());
        } else {
            // 신규 사용자인 경우, 임시 User 객체를 생성합니다.
            // 아직 우리 서비스의 회원은 아니므로, DB에 저장하지는 않습니다.
            // 성공 핸들러에서 isNewUser 플래그로 분기 처리합니다.
            user = null; // 신규 사용자를 의미
            log.info("신규 소셜 연동 유저. Provider: {}, ProviderId: {}", provider, providerId);
        }

        // CustomOAuth2User 객체를 생성하여 반환
        return new CustomOAuth2User(oAuth2User, provider, providerId, user);
    }
}
