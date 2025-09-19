package com.itplace.userapi.user.repository;

import com.itplace.userapi.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByPhoneNumber(String phoneNumber);

    Optional<User> findByEmailOrPhoneNumber(String email, String phoneNumber);

    Optional<User> findByPhoneNumberAndName(String phoneNumber, String name);
}
