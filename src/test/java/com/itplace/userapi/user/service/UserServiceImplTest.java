package com.itplace.userapi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.exception.PasswordMismatchException;
import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.user.dto.request.ChangePasswordRequest;
import com.itplace.userapi.user.dto.request.MembershipProfileUpdateRequest;
import com.itplace.userapi.user.dto.response.UserInfoResponse;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.AuthCredentialRepository;
import com.itplace.userapi.user.repository.SocialAccountRepository;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private OtpUtil otpUtil;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FavoriteRepository favoriteRepository;

    @Mock
    private SocialAccountRepository socialAccountRepository;

    @Mock
    private AuthCredentialRepository authCredentialRepository;

    @Mock
    private PrincipalDetails principalDetails;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void changePassword_rejectsMismatchedNewPasswordConfirmation() {
        User user = User.builder()
                .id(7L)
                .role(Role.USER)
                .build();
        AuthCredential localCredential = AuthCredential.localPassword(user, "encoded-old");
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("old-password");
        request.setNewPassword("new-password");
        request.setNewPasswordConfirm("different-password");

        when(principalDetails.getUserId()).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(localCredential));
        when(passwordEncoder.matches("old-password", "encoded-old")).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(principalDetails, request))
                .isInstanceOf(PasswordMismatchException.class);
        verify(passwordEncoder, never()).encode("new-password");
    }

    @Test
    void changePasswordUpdatesLocalPasswordCredential() {
        User user = User.builder()
                .id(7L)
                .role(Role.USER)
                .build();
        AuthCredential localCredential = AuthCredential.localPassword(user, "encoded-old");
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("old-password");
        request.setNewPassword("new-password");
        request.setNewPasswordConfirm("new-password");

        when(principalDetails.getUserId()).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(localCredential));
        when(passwordEncoder.matches("old-password", "encoded-old")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-new");

        userService.changePassword(principalDetails, request);

        assertThat(localCredential.getPasswordHash()).isEqualTo("encoded-new");
    }

    @Test
    void withdrawSkipsPasswordCheckForOAuthOnlyUser() {
        User user = User.builder()
                .id(7L)
                .role(Role.USER)
                .build();

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.empty());

        userService.withdraw(7L, null);

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(favoriteRepository).deleteByUser_Id(7L);
        verify(authCredentialRepository).deleteByUser_Id(7L);
        verify(socialAccountRepository).deleteByUser_Id(7L);
        verify(userRepository).delete(user);
    }

    @Test
    void withdrawRequiresPasswordForLocalUserLinkedToOAuth() {
        User user = User.builder()
                .id(7L)
                .role(Role.USER)
                .build();
        AuthCredential localCredential = AuthCredential.localPassword(user, "encoded-password");

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(localCredential));

        assertThatThrownBy(() -> userService.withdraw(7L, null))
                .isInstanceOf(PasswordMismatchException.class);

        verify(favoriteRepository, never()).deleteByUser_Id(7L);
        verify(authCredentialRepository, never()).deleteByUser_Id(7L);
        verify(socialAccountRepository, never()).deleteByUser_Id(7L);
        verify(userRepository, never()).delete(user);
    }

    @Test
    void withdrawRequiresPasswordForLocalUser() {
        User user = User.builder()
                .id(7L)
                .role(Role.USER)
                .build();
        AuthCredential localCredential = AuthCredential.localPassword(user, "encoded-password");

        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(localCredential));

        assertThatThrownBy(() -> userService.withdraw(7L, ""))
                .isInstanceOf(PasswordMismatchException.class);

        verify(favoriteRepository, never()).deleteByUser_Id(7L);
        verify(authCredentialRepository, never()).deleteByUser_Id(7L);
        verify(socialAccountRepository, never()).deleteByUser_Id(7L);
        verify(userRepository, never()).delete(user);
    }

    @Test
    void updateMembershipProfileUpdatesCarrierGradeAndResetsVerification() {
        User user = User.builder()
                .id(7L)
                .carrier(Carrier.LGU)
                .membershipGradeCode(Grade.VIP)
                .membershipVerified(true)
                .role(Role.USER)
                .build();
        MembershipProfileUpdateRequest request = new MembershipProfileUpdateRequest();
        request.setCarrier(Carrier.KT);
        request.setMembershipGradeCode(Grade.KT_GOLD);

        when(principalDetails.getUserId()).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        UserInfoResponse response = userService.updateMembershipProfile(principalDetails, request);

        assertThat(user.getCarrier()).isEqualTo(Carrier.KT);
        assertThat(user.getMembershipGradeCode()).isEqualTo(Grade.KT_GOLD);
        assertThat(user.getMembershipVerified()).isFalse();
        assertThat(response.getCarrier()).isEqualTo(Carrier.KT);
        assertThat(response.getMembershipGradeCode()).isEqualTo(Grade.KT_GOLD);
        assertThat(response.getMembershipVerified()).isFalse();
        verify(userRepository).findById(7L);
    }

    @Test
    void updateMembershipProfileRejectsInvalidCarrierGradePair() {
        MembershipProfileUpdateRequest request = new MembershipProfileUpdateRequest();
        request.setCarrier(Carrier.SKT);
        request.setMembershipGradeCode(Grade.VIP);

        assertThatThrownBy(() -> userService.updateMembershipProfile(principalDetails, request))
                .isInstanceOf(com.itplace.userapi.user.exception.InvalidMembershipProfileException.class);
    }
}
