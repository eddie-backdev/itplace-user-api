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
import com.itplace.userapi.security.exception.InvalidCredentialsException;
import com.itplace.userapi.security.exception.PasswordMismatchException;
import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.user.dto.request.ChangePasswordRequest;
import com.itplace.userapi.user.dto.request.MembershipProfileUpdateRequest;
import com.itplace.userapi.user.dto.request.ResetPasswordRequest;
import com.itplace.userapi.user.dto.response.UserInfoResponse;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.AuthCredentialRepository;
import com.itplace.userapi.user.repository.SocialAccountRepository;
import com.itplace.userapi.user.repository.UserRepository;
import com.itplace.userapi.user.support.MembershipProfileValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
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

    @Mock
    private MembershipProfileValidator membershipProfileValidator;

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
    void changePasswordRejectsWrongCurrentPasswordWithoutChangingCredential() {
        User user = User.builder().id(7L).role(Role.USER).build();
        AuthCredential credential = AuthCredential.localPassword(user, "encoded-old");
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("wrong-password");
        request.setNewPassword("new-password");
        request.setNewPasswordConfirm("new-password");
        when(principalDetails.getUserId()).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("wrong-password", "encoded-old")).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(principalDetails, request))
                .isInstanceOf(PasswordMismatchException.class);

        assertThat(credential.getPasswordHash()).isEqualTo("encoded-old");
        verify(passwordEncoder, never()).encode(anyString());
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
    void withdrawRejectsIncorrectPasswordBeforeDeletingAccountData() {
        User user = User.builder().id(7L).role(Role.USER).build();
        AuthCredential credential = AuthCredential.localPassword(user, "encoded-password");
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(credential));
        when(passwordEncoder.matches("wrong-password", "encoded-password")).thenReturn(false);

        assertThatThrownBy(() -> userService.withdraw(7L, "wrong-password"))
                .isInstanceOf(PasswordMismatchException.class);

        verify(favoriteRepository, never()).deleteByUser_Id(7L);
        verify(authCredentialRepository, never()).deleteByUser_Id(7L);
        verify(socialAccountRepository, never()).deleteByUser_Id(7L);
        verify(userRepository, never()).delete(user);
    }

    @Test
    void resetPasswordKeepsResetTokenWhenConfirmationDoesNotMatch() {
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        ResetPasswordRequest request = resetPasswordRequest("new-password", "different-password");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("resetPassword:one-time-token")).thenReturn("hong@example.com");

        assertThatThrownBy(() -> userService.resetPassword(request))
                .isInstanceOf(PasswordMismatchException.class);

        verify(redisTemplate, never()).delete("resetPassword:one-time-token");
        verify(userRepository, never()).findByEmail("hong@example.com");
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void resetPasswordRejectsEmailMismatchWithoutConsumingToken() {
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        ResetPasswordRequest request = resetPasswordRequest("new-password", "new-password");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("resetPassword:one-time-token")).thenReturn("other@example.com");

        assertThatThrownBy(() -> userService.resetPassword(request))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(redisTemplate, never()).delete("resetPassword:one-time-token");
        verify(userRepository, never()).findByEmail("hong@example.com");
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void withdrawRejectsUnknownUserBeforeAnyCleanup() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.withdraw(7L, null))
                .isInstanceOf(com.itplace.userapi.user.exception.UserNotFoundException.class);

        verify(authCredentialRepository, never()).findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD);
        verify(favoriteRepository, never()).deleteByUser_Id(7L);
        verify(authCredentialRepository, never()).deleteByUser_Id(7L);
        verify(socialAccountRepository, never()).deleteByUser_Id(7L);
        verify(userRepository, never()).delete(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void changePasswordRejectsMissingUserBeforeCredentialLookup() {
        when(principalDetails.getUserId()).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changePassword(principalDetails, new ChangePasswordRequest()))
                .isInstanceOf(com.itplace.userapi.user.exception.UserNotFoundException.class);

        verify(authCredentialRepository, never()).findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD);
        verify(passwordEncoder, never()).encode(anyString());
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
        when(membershipProfileValidator.isValid(Carrier.KT, Grade.KT_GOLD)).thenReturn(true);

        UserInfoResponse response = userService.updateMembershipProfile(principalDetails, request);

        assertThat(user.getCarrier()).isEqualTo(Carrier.KT);
        assertThat(user.getMembershipGradeCode()).isEqualTo(Grade.KT_GOLD);
        assertThat(user.getMembershipVerified()).isFalse();
        assertThat(response.getCarrier()).isEqualTo(Carrier.KT);
        assertThat(response.getMembershipGradeCode()).isEqualTo(Grade.KT_GOLD);
        assertThat(response.getMembershipVerified()).isFalse();
        assertThat(response.getPhoneVerified()).isFalse();
        verify(userRepository).findById(7L);
    }

    @Test
    void userInfoTreatsPersistedPhoneAsVerifiedAndNullAsUnverified() {
        User verifiedUser = User.builder().id(7L).phoneNumber("01012345678").role(Role.USER).build();
        User oauthUser = User.builder().id(8L).role(Role.USER).build();

        assertThat(UserInfoResponse.from(verifiedUser).getPhoneVerified()).isTrue();
        assertThat(UserInfoResponse.from(oauthUser).getPhoneVerified()).isFalse();
    }

    @Test
    void updateMembershipProfileRejectsInvalidCarrierGradePair() {
        MembershipProfileUpdateRequest request = new MembershipProfileUpdateRequest();
        request.setCarrier(Carrier.SKT);
        request.setMembershipGradeCode(Grade.VIP);

        when(membershipProfileValidator.isValid(Carrier.SKT, Grade.VIP)).thenReturn(false);

        assertThatThrownBy(() -> userService.updateMembershipProfile(principalDetails, request))
                .isInstanceOf(com.itplace.userapi.user.exception.InvalidMembershipProfileException.class);
    }

    private ResetPasswordRequest resetPasswordRequest(String password, String confirmation) {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setResetPasswordToken("one-time-token");
        request.setEmail("hong@example.com");
        request.setNewPassword(password);
        request.setNewPasswordConfirm(confirmation);
        return request;
    }
}
