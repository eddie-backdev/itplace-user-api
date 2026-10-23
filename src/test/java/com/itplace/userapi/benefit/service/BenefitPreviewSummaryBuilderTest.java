package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.BenefitFact;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.OfferResolution;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.reference.ReferenceDataService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenefitPreviewSummaryBuilderTest {
    private final BenefitPreviewSummaryBuilder builder = new BenefitPreviewSummaryBuilder(
            new BenefitOfferMatcher(mock(ReferenceDataService.class)));

    @Test
    void buildsOneSummaryFromTheExplicitlyLinkedOfferAndKeepsTypedConditions() {
        BenefitOffer offer = new BenefitOffer("tuesday", "화요일 혜택", null, UsageType.OFFLINE,
                List.of("SKT_LITE"), true, List.of("신선식품"), List.of("매주 화요일"), List.of(), List.of(), List.of(), List.of(),
                null, List.of(
                fact("DISCOUNT_RATE", "10", "MAX"), fact("MIN_SPEND", "10000", "MIN"),
                fact("USAGE_LIMIT", "1", null, "DAY", "VIP_CHOICE")));
        BenefitConditions conditions = new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(offer), OfferResolution.SINGLE, null);
        CarrierTierBenefit linked = tier("tuesday", Grade.SKT_LITE, "화요일 할인\n회원 초이스 한도");
        CarrierTierBenefit unlinked = tier(null, Grade.SKT_LITE, "다른 혜택과 공유하는 문맥");

        var summary = builder.build(conditions, List.of(linked, unlinked), Carrier.SKT, List.of(Grade.SKT_LITE));

        assertThat(summary).isNotNull();
        assertThat(summary.offerKey()).isEqualTo("tuesday");
        assertThat(summary.title()).isEqualTo("최대 10% 할인");
        assertThat(summary.condition()).contains("신선식품", "매주 화요일", "최소 10000원 이상 결제");
        assertThat(summary.limit()).contains("VIP 초이스 통합 1회/일");
        assertThat(summary.grades()).containsExactly("SKT_LITE");
        assertThat(summary.version()).isEqualTo("1");
        assertThat(summary.condition()).doesNotContain("다른 혜택");
    }

    @Test
    void omitsOperationalProductProseButKeepsConciseManufacturedDrinkScope() {
        BenefitOffer points = new BenefitOffer("points", "포인트 적립", null, UsageType.OFFLINE,
                List.of("SKT_SILVER"), false, List.of("적립된 포인트는 제휴포인트로 전환됩니다."),
                List.of("쿠폰은 현금으로 교환할 수 없습니다."), List.of(), List.of(), List.of(), List.of(),
                null, List.of(fact("REWARD_POINTS", "5", "EXACT")));
        BenefitConditions pointConditions = new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(points), OfferResolution.SINGLE, null);

        var pointSummary = builder.build(pointConditions, List.of(), Carrier.SKT, List.of());

        assertThat(pointSummary.condition()).isEqualTo("대상 상품·이용 조건은 상세에서 확인");
        assertThat(pointSummary.title()).doesNotContain("전환");

        BenefitOffer gongcha = new BenefitOffer("gongcha", "공차 할인", null, UsageType.OFFLINE,
                List.of("SKT_SILVER"), false, List.of("제조 음료에 한함"), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, List.of(fact("DISCOUNT_RATE", "10", "EXACT")));
        BenefitConditions drinkConditions = new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(gongcha), OfferResolution.SINGLE, null);

        var drinkSummary = builder.build(drinkConditions, List.of(), Carrier.SKT, List.of());

        assertThat(drinkSummary.condition()).isEqualTo("제조 음료에 한함");
    }

    @Test
    void neverPromotesUnscopedRawLimitTextIntoTheCardSummary() {
        BenefitOffer offer = new BenefitOffer("silver", "다락 할인", null, UsageType.OFFLINE,
                List.of("SKT_SILVER"), false, List.of("다락 상품"), List.of(), List.of(),
                List.of("T멤버십 VIP 고객님 최초 1개월 무료"), List.of(), List.of());
        BenefitConditions conditions = new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(offer), OfferResolution.SINGLE, null);

        var summary = builder.build(conditions, List.of(), Carrier.SKT, List.of());

        assertThat(summary).isNotNull();
        assertThat(summary.limit()).isEmpty();
    }

    @Test
    void selectsFirstExplicitOfferWhenMultipleOffersAreAssignedAcrossThePreview() {
        BenefitOffer one = offer("one");
        BenefitOffer two = offer("two");
        BenefitConditions conditions = new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(one, two), OfferResolution.SCOPED, null);

        var summary = builder.build(conditions, List.of(tier("one", Grade.SKT_LITE, "A"),
                tier("two", Grade.SKT_LITE, "B")), Carrier.SKT, List.of());

        assertThat(summary).isNotNull();
        assertThat(summary.offerKey()).isEqualTo("one");
    }

    private static BenefitOffer offer(String key) {
        return new BenefitOffer(key, key, null, UsageType.BOTH, List.of("SKT_LITE"), true,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static BenefitFact fact(String type, String value, String qualifier) {
        return fact(type, value, qualifier, null, null);
    }

    private static BenefitFact fact(String type, String value, String qualifier, String period, String sharedGroup) {
        return new BenefitFact(type, new BigDecimal(value), null, period, null, null, sharedGroup, qualifier, null, null);
    }

    private static CarrierTierBenefit tier(String key, Grade grade, String context) {
        BenefitCarrierPolicy policy = mock(BenefitCarrierPolicy.class);
        return CarrierTierBenefit.builder().benefitCarrierPolicy(policy).offerKey(key).grade(grade).context(context).build();
    }
}
