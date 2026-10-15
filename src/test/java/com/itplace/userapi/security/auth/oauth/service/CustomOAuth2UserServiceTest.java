package com.itplace.userapi.security.auth.oauth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.auth.oauth.dto.CustomOAuth2User;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.AuthCredentialRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class CustomOAuth2UserServiceTest {

    @Mock
    private AuthCredentialRepository authCredentialRepository;

    @Test
    void returnsNewUserOnlyForKakaoWithVerifiedEmail() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://provider.invalid/me"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"id":123,"kakao_account":{"email":"new@example.com","is_email_valid":true,
                        "is_email_verified":true,"profile":{"nickname":"New"}}}
                        """, MediaType.APPLICATION_JSON));
        CustomOAuth2UserService service = service(restTemplate);
        when(authCredentialRepository.findByTypeAndProviderAndProviderUserId(
                AuthCredentialType.OAUTH, "kakao", "123")).thenReturn(Optional.empty());

        CustomOAuth2User user = (CustomOAuth2User) service.loadUser(userRequest("kakao", "id"));

        assertThat(user.isNewUser()).isTrue();
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getNickname()).isEqualTo("New");
        verify(authCredentialRepository).findByTypeAndProviderAndProviderUserId(
                AuthCredentialType.OAUTH, "kakao", "123");
        server.verify();
    }

    @Test
    void rejectsUnsupportedRegistrationWithoutCallingProvider() {
        CustomOAuth2UserService service = service(new RestTemplate());

        assertThatThrownBy(() -> service.loadUser(userRequest("google", "id")))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void rejectsUnverifiedProviderEmailBeforeTreatingItAsNewUser() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://provider.invalid/me"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"id":"123","kakao_account":{"email":"new@example.com","is_email_valid":true,
                        "is_email_verified":false}}
                        """, MediaType.APPLICATION_JSON));
        CustomOAuth2UserService service = service(restTemplate);

        assertThatThrownBy(() -> service.loadUser(userRequest("kakao", "id")))
                .isInstanceOf(OAuth2AuthenticationException.class);

        verify(authCredentialRepository).findByTypeAndProviderAndProviderUserId(
                AuthCredentialType.OAUTH, "kakao", "123");
        server.verify();
    }

    @Test
    void returnsTheUserLinkedToAValidExistingCredential() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://provider.invalid/me"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess("""
                        {"id":"123","kakao_account":{"email":"known@example.com","is_email_valid":true,
                        "is_email_verified":true}}
                        """, MediaType.APPLICATION_JSON));
        User existing = User.builder().id(7L).email("known@example.com").role(Role.USER).build();
        when(authCredentialRepository.findByTypeAndProviderAndProviderUserId(
                AuthCredentialType.OAUTH, "kakao", "123"))
                .thenReturn(Optional.of(AuthCredential.oauth(existing, "kakao", "123")));

        CustomOAuth2User result = (CustomOAuth2User) service(restTemplate).loadUser(userRequest("kakao", "id"));

        assertThat(result.isNewUser()).isFalse();
        assertThat(result.getUser()).isSameAs(existing);
        server.verify();
    }

    @Test
    void rejectsMalformedOrMissingProviderIdentity() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://provider.invalid/me"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"kakao_account\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service(restTemplate).loadUser(userRequest("kakao", "id")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        org.mockito.Mockito.verifyNoInteractions(authCredentialRepository);
        server.verify();
    }

    @Test
    void rejectsNonnumericProviderIdentityBeforeCredentialLookup() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://provider.invalid/me"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"id\":\"not-a-provider-id\",\"kakao_account\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service(restTemplate).loadUser(userRequest("kakao", "id")))
                .isInstanceOf(OAuth2AuthenticationException.class);
        org.mockito.Mockito.verifyNoInteractions(authCredentialRepository);
        server.verify();
    }

    private CustomOAuth2UserService service(RestTemplate restTemplate) {
        CustomOAuth2UserService service = new CustomOAuth2UserService(authCredentialRepository);
        service.setRestOperations(restTemplate);
        return service;
    }

    private OAuth2UserRequest userRequest(String registrationId, String usernameAttribute) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .clientSecret("client-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://app.invalid/callback")
                .scope("profile")
                .authorizationUri("https://provider.invalid/authorize")
                .tokenUri("https://provider.invalid/token")
                .userInfoUri("https://provider.invalid/me")
                .userNameAttributeName(usernameAttribute)
                .clientName("Provider")
                .build();
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "access-token",
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(60));
        return new OAuth2UserRequest(registration, token);
    }
}
