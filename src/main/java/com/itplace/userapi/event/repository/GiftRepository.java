package com.itplace.userapi.event.repository;

import com.itplace.userapi.event.entity.Gift;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;


public interface GiftRepository extends JpaRepository<Gift, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Gift g WHERE g.giftCount > 0")
    List<Gift> findAvailableGiftsForUpdate();
}
