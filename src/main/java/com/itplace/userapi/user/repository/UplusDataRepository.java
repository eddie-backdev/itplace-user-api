package com.itplace.userapi.user.repository;

import com.itplace.userapi.user.entity.UplusData;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UplusDataRepository extends JpaRepository<UplusData, Long> {
    Optional<UplusData> findByPhoneNumber(String phoneNumber);
}
