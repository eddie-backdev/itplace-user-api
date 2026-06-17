package com.itplace.userapi.user.repository;

import com.itplace.userapi.user.entity.AuthCredential;
import com.itplace.userapi.user.entity.AuthCredentialType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthCredentialRepository extends JpaRepository<AuthCredential, Long> {

    Optional<AuthCredential> findByTypeAndProviderAndProviderUserId(
            AuthCredentialType type,
            String provider,
            String providerUserId
    );

    Optional<AuthCredential> findByUser_IdAndType(Long userId, AuthCredentialType type);

    boolean existsByUser_IdAndType(Long userId, AuthCredentialType type);

    boolean existsByUser_IdAndTypeAndProviderAndProviderUserId(
            Long userId,
            AuthCredentialType type,
            String provider,
            String providerUserId
    );

    void deleteByUser_Id(Long userId);
}
