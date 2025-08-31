package com.itplace.userapi.user.repository;

import com.itplace.userapi.user.entity.SocialAccount;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
    Optional<SocialAccount> findByProviderAndProviderId(String provider, String providerId);

    void deleteByUser_Id(Long userId);
}
