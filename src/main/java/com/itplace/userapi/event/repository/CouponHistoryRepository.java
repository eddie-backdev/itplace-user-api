package com.itplace.userapi.event.repository;

import com.itplace.userapi.event.entity.CouponHistory;
import com.itplace.userapi.user.entity.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {
    List<CouponHistory> findByUser(User user);
}
