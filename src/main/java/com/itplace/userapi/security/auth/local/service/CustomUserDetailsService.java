package com.itplace.userapi.security.auth.local.service;

import com.itplace.userapi.security.auth.local.dto.CustomUserDetails;
import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.AuthCredentialRepository;
import com.itplace.userapi.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsPasswordService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService, UserDetailsPasswordService {

    private final UserRepository userRepository;
    private final AuthCredentialRepository authCredentialRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("아이디 / 비밀번호를 다시 확인해주세요."));
        AuthCredential credential = authCredentialRepository.findByUser_IdAndType(user.getId(), AuthCredentialType.LOCAL_PASSWORD)
                .orElseThrow(() -> new UsernameNotFoundException("아이디 / 비밀번호를 다시 확인해주세요."));

        return new CustomUserDetails(user, credential.getPasswordHash());
    }

    @Override
    @Transactional
    public UserDetails updatePassword(UserDetails userDetails, String newPassword) {
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new UsernameNotFoundException("아이디 / 비밀번호를 다시 확인해주세요."));
        AuthCredential credential = authCredentialRepository.findByUser_IdAndType(user.getId(), AuthCredentialType.LOCAL_PASSWORD)
                .orElseThrow(() -> new UsernameNotFoundException("아이디 / 비밀번호를 다시 확인해주세요."));

        credential.setPasswordHash(newPassword);
        return new CustomUserDetails(user, credential.getPasswordHash());
    }
}
