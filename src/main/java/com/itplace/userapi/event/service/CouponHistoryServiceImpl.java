package com.itplace.userapi.event.service;

import com.itplace.userapi.event.GiftCode;
import com.itplace.userapi.event.dto.HistoryResponse;
import com.itplace.userapi.event.entity.CouponHistory;
import com.itplace.userapi.event.entity.ResultType;
import com.itplace.userapi.event.exception.InvalidResultTypeException;
import com.itplace.userapi.event.repository.CouponHistoryRepository;
import com.itplace.userapi.security.exception.UserNotFoundException;
import com.itplace.userapi.user.UserCode;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CouponHistoryServiceImpl implements CouponHistoryService {

    private final UserRepository userRepository;
    private final CouponHistoryRepository couponHistoryRepository;

    @Override
    public List<HistoryResponse> getCouponHistory(Long userId, String type) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(UserCode.USER_NOT_FOUND));

        List<CouponHistory> historyList = couponHistoryRepository.findByUser(user);

        if (type == null || type.isEmpty()) {
            return historyList.stream()
                    .map(history -> new HistoryResponse(
                            user.getId(),
                            history.getHistoryId(),
                            history.getGift() != null ? history.getGift().getGiftName() : null,
                            history.getResult(),
                            history.getUsedDate()
                    )).toList();
        }

        ResultType filterType;
        try {
            filterType = ResultType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new InvalidResultTypeException(GiftCode.INVALID_RESULT_TYPE);
        }

        return historyList.stream()
                .filter(history -> history.getResult() == filterType)
                .map(history -> new HistoryResponse(
                        user.getId(),
                        history.getHistoryId(),
                        history.getGift() != null ? history.getGift().getGiftName() : null,
                        history.getResult(),
                        history.getUsedDate()
                )).toList();
    }
}
