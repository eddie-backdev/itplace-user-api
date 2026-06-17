package com.itplace.userapi.security.auth.local.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.auth.local.dto.CustomUserDetails;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.AuthCredentialRepository;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthCredentialRepository authCredentialRepository;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void updatePasswordReplacesLocalPasswordCredentialWithEncodedPassword() {
        User user = User.builder()
                .id(7L)
                .email("local@example.com")
                .role(Role.USER)
                .build();
        AuthCredential credential = AuthCredential.localPassword(user, "plain-password");
        when(userRepository.findByEmail("local@example.com")).thenReturn(Optional.of(user));
        when(authCredentialRepository.findByUser_IdAndType(7L, AuthCredentialType.LOCAL_PASSWORD))
                .thenReturn(Optional.of(credential));

        UserDetails updated = customUserDetailsService.updatePassword(
                new CustomUserDetails(user, "plain-password"),
                "$2a$10$encoded-password"
        );

        assertThat(credential.getPasswordHash()).isEqualTo("$2a$10$encoded-password");
        assertThat(updated.getPassword()).isEqualTo("$2a$10$encoded-password");
    }
}
