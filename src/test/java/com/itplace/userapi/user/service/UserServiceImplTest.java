package com.itplace.userapi.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itplace.userapi.favorite.repository.FavoriteRepository;
import com.itplace.userapi.security.auth.common.PrincipalDetails;
import com.itplace.userapi.security.exception.PasswordMismatchException;
import com.itplace.userapi.security.verification.OtpUtil;
import com.itplace.userapi.user.dto.request.ChangePasswordRequest;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.MembershipRepository;
import com.itplace.userapi.user.repository.SocialAccountRepository;
import com.itplace.userapi.user.repository.UplusDataRepository;
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
    private UplusDataRepository uplusDataRepository;

    @Mock
    private MembershipRepository membershipRepository;

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
    private PrincipalDetails principalDetails;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void changePassword_rejectsMismatchedNewPasswordConfirmation() {
        User user = User.builder()
                .id(7L)
                .password("encoded-old")
                .role(Role.USER)
                .build();
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("old-password");
        request.setNewPassword("new-password");
        request.setNewPasswordConfirm("different-password");

        when(principalDetails.getUserId()).thenReturn(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("old-password", "encoded-old")).thenReturn(true);

        assertThatThrownBy(() -> userService.changePassword(principalDetails, request))
                .isInstanceOf(PasswordMismatchException.class);
        verify(passwordEncoder, never()).encode("new-password");
    }
}
