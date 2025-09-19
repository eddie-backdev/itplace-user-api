package com.itplace.userapi.security.auth.local.service;

import com.itplace.userapi.security.auth.local.dto.CustomUserDetails;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("아이디 / 비밀번호를 다시 확인해주세요."));
        return new CustomUserDetails(user);
    }
}
