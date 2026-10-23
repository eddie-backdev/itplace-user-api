package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TierBenefitSemanticsTest {

    @Test
    void legacyAllFlagsDoNotTurnMixedGradeContextsIntoAllGradeBenefits() {
        List<CarrierTierBenefit> outback = List.of(
                tier(Grade.SKT_VIP, "15% 할인"),
                tier(Grade.SKT_GOLD, "15% 할인"),
                tier(Grade.SKT_SILVER, "5% 할인")
        );

        assertThat(TierBenefitSemantics.allGradeContexts(outback, sktGrades())).isEmpty();
    }

    @Test
    void sameContextForEveryApplicableGradeRemainsAllGradeBenefit() {
        List<CarrierTierBenefit> allGrade = List.of(
                tier(Grade.SKT_VIP, "전 등급 혜택"),
                tier(Grade.SKT_GOLD, "전 등급 혜택"),
                tier(Grade.SKT_SILVER, "전 등급 혜택"),
                tier(Grade.SKT_LITE, "전 등급 혜택")
        );

        assertThat(TierBenefitSemantics.allGradeContexts(allGrade, sktGrades()))
                .containsExactly("전 등급 혜택");
    }

    @Test
    void lguVipVvipAndDiamondAreNotAllGradesWithoutBasic() {
        List<CarrierTierBenefit> partial = List.of(
                tier(Grade.VIP, "우수 등급 혜택"),
                tier(Grade.VVIP, "우수 등급 혜택"),
                tier(Grade.DIAMOND, "우수 등급 혜택")
        );
        List<CarrierTierBenefit> all = List.of(
                tier(Grade.BASIC, "전 등급 혜택"),
                tier(Grade.VIP, "전 등급 혜택"),
                tier(Grade.VVIP, "전 등급 혜택"),
                tier(Grade.DIAMOND, "전 등급 혜택")
        );

        assertThat(TierBenefitSemantics.allGradeContexts(partial, lguGrades())).isEmpty();
        assertThat(TierBenefitSemantics.allGradeContexts(all, lguGrades())).containsExactly("전 등급 혜택");
    }

    @Test
    void oldThreeSktGradesDoNotQualifyAsAllGradesAfterLiteWasAdded() {
        List<CarrierTierBenefit> oldGradeSet = List.of(
                tier(Grade.SKT_VIP, "전 등급 혜택"),
                tier(Grade.SKT_GOLD, "전 등급 혜택"),
                tier(Grade.SKT_SILVER, "전 등급 혜택")
        );

        assertThat(TierBenefitSemantics.allGradeContexts(oldGradeSet, sktGrades())).isEmpty();
    }

    @Test
    void databaseAddedGradeParticipatesInAllGradeDetermination() {
        var platinum = Grade.of("SKT_PLATINUM");
        List<CarrierTierBenefit> existing = List.of(tier(Grade.SKT_LITE, "모든 회원"), tier(Grade.SKT_SILVER, "모든 회원"),
                tier(Grade.SKT_GOLD, "모든 회원"), tier(Grade.SKT_VIP, "모든 회원"));
        assertThat(TierBenefitSemantics.allGradeContexts(existing, Set.of(Grade.SKT_LITE, Grade.SKT_SILVER,
                Grade.SKT_GOLD, Grade.SKT_VIP, platinum))).isEmpty();
        existing = new java.util.ArrayList<>(existing);
        existing.add(tier(platinum, "모든 회원"));
        assertThat(TierBenefitSemantics.allGradeContexts(existing, Set.of(Grade.SKT_LITE, Grade.SKT_SILVER,
                Grade.SKT_GOLD, Grade.SKT_VIP, Grade.of("SKT_PLATINUM")))).containsExactly("모든 회원");
    }

    private Set<Grade> sktGrades() { return Set.of(Grade.SKT_LITE, Grade.SKT_SILVER, Grade.SKT_GOLD, Grade.SKT_VIP); }
    private Set<Grade> lguGrades() { return Set.of(Grade.BASIC, Grade.VIP, Grade.VVIP, Grade.DIAMOND); }

    private CarrierTierBenefit tier(Grade grade, String context) {
        return CarrierTierBenefit.builder().grade(grade).context(context).isAll(true).build();
    }
}
