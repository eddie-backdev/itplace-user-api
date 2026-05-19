package com.itplace.userapi.security.auth.local.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.itplace.userapi.security.auth.local.dto.CustomUserDetails;
import com.itplace.userapi.user.entity.Role;
import com.itplace.userapi.user.entity.User;
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

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void updatePasswordReplacesLegacyPasswordWithEncodedPassword() {
        User user = User.builder()
                .email("legacy@example.com")
                .password("plain-password")
                .role(Role.USER)
                .build();
        when(userRepository.findByEmail("legacy@example.com")).thenReturn(Optional.of(user));

        UserDetails updated = customUserDetailsService.updatePassword(
                new CustomUserDetails(user),
                "$2a$10$encoded-password"
        );

        assertThat(user.getPassword()).isEqualTo("$2a$10$encoded-password");
        assertThat(updated.getPassword()).isEqualTo("$2a$10$encoded-password");
    }
}
