package com.itplace.userapi.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.event.entity.Gift;
import com.itplace.userapi.event.repository.CouponHistoryRepository;
import com.itplace.userapi.event.repository.GiftRepository;
import com.itplace.userapi.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ScratchServiceImplTest {

    @Mock
    private GiftRepository giftRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CouponHistoryRepository couponHistoryRepository;

    @Test
    void weightedRandomGift_returnsNullWhenAllAvailableGiftsHaveNoPositiveWeight() {
        ScratchServiceImpl scratchService = new ScratchServiceImpl(
                giftRepository, userRepository, couponHistoryRepository);

        Gift zeroWeightGift = Gift.builder()
                .giftId(1L)
                .giftName("zero")
                .giftCount(1)
                .total(0)
                .build();
        Gift nullWeightGift = Gift.builder()
                .giftId(2L)
                .giftName("null")
                .giftCount(1)
                .total(null)
                .build();

        Gift selected = ReflectionTestUtils.invokeMethod(
                scratchService, "weightedRandomGift", List.of(zeroWeightGift, nullWeightGift));

        assertThat(selected).isNull();
    }

    @Test
    void weightedRandomGift_ignoresInvalidWeightsWhenPositiveWeightExists() {
        ScratchServiceImpl scratchService = new ScratchServiceImpl(
                giftRepository, userRepository, couponHistoryRepository);

        Gift invalidGift = Gift.builder()
                .giftId(1L)
                .giftName("invalid")
                .giftCount(1)
                .total(0)
                .build();
        Gift validGift = Gift.builder()
                .giftId(2L)
                .giftName("valid")
                .giftCount(1)
                .total(3)
                .build();

        Gift selected = ReflectionTestUtils.invokeMethod(
                scratchService, "weightedRandomGift", List.of(invalidGift, validGift));

        assertThat(selected).isSameAs(validGift);
    }
}
