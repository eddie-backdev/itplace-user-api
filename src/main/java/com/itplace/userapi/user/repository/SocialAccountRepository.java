package com.itplace.userapi.user.repository;

import com.itplace.userapi.user.entity.SocialAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SocialAccountRepository extends JpaRepository<SocialAccount, Long> {
    void deleteByUser_Id(Long userId);
}
