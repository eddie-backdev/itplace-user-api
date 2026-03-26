package com.itplace.userapi.event.service;

import com.itplace.userapi.event.dto.response.ScratchResult;
import com.itplace.userapi.event.entity.CouponHistory;
import com.itplace.userapi.event.entity.Gift;
import com.itplace.userapi.event.entity.ResultType;
import com.itplace.userapi.event.repository.CouponHistoryRepository;
import com.itplace.userapi.event.repository.GiftRepository;
import com.itplace.userapi.security.SecurityCode;
import com.itplace.userapi.user.exception.UserNotFoundException;
import com.itplace.userapi.user.entity.User;
import com.itplace.userapi.user.repository.UserRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ScratchServiceImpl implements ScratchService {

    private final GiftRepository giftRepository;
    private final Random random = new Random();
    private final UserRepository userRepository;
    private final CouponHistoryRepository couponHistoryRepository;

    @Transactional
    public ScratchResult scratch(Long userId) {

        // 사용자 찾기
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(SecurityCode.USER_NOT_FOUND));

        // 쿠폰 차감 가능 여부 확인
        if (user.getCoupon() < 1) {
            return new ScratchResult(false, "별이 부족합니다. 별을 다시 모은 후 시도해주세요.", null);
        }

        // 쿠폰 차감
        user.setCoupon(user.getCoupon() - 1);
        userRepository.save(user);

        // 당첨 확률 5%
        boolean isSuccess = random.nextInt(100) < 5;

        Gift selectedGift = null;
        if (isSuccess) {
            List<Gift> availableGifts = giftRepository.findAvailableGiftsForUpdate();
            if (!availableGifts.isEmpty()) {
                selectedGift = weightedRandomGift(availableGifts);
                selectedGift.setGiftCount(selectedGift.getGiftCount() - 1);
                giftRepository.save(selectedGift);
            } else {
                isSuccess = false;
            }
        }

        // 히스토리 저장
        couponHistoryRepository.save(CouponHistory.builder()
                .user(user)
                .gift(isSuccess ? selectedGift : null)
                .result(isSuccess ? ResultType.SUCCESS : ResultType.FAIL)
                .usedDate(LocalDate.now())
                .build());

        if (isSuccess && selectedGift != null) {
            return new ScratchResult(true, "🎉 " + selectedGift.getGiftName() + " 당첨!", selectedGift);
        } else {
            return new ScratchResult(false, "꽝입니다. 다음 기회를 노려보세요!", null);
        }

    }

    // 상품별 당첨 가중치 조절
    private Gift weightedRandomGift(List<Gift> gifts) {
        int totalWeight = gifts.stream()
                .mapToInt(Gift::getTotal)
                .sum();

        int randomValue = random.nextInt(totalWeight);
        int cumulativeWeight = 0;

        for (Gift gift : gifts) {
            cumulativeWeight += gift.getTotal();
            if (randomValue < cumulativeWeight) {
                return gift;
            }
        }

        return gifts.get(0);
    }


}
