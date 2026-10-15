package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MembershipOptionType;
import com.itplace.userapi.partner.entity.Partner;
import org.junit.jupiter.api.Test;

class MembershipDisplayNameTest {
    @Test
    void normalizesGeneratedMembershipNamesAndKeepsOptionOnCompatibilityName() {
        Partner gs25 = Partner.builder().partnerName("GS25").build();
        Benefit benefit = Benefit.builder().partner(gs25).build();
        BenefitCarrierPolicy discount = policy(benefit, "GS25 할인형", MembershipOptionType.DISCOUNT);
        BenefitCarrierPolicy accrual = policy(benefit, "GS25 적립형", MembershipOptionType.POINT_ACCRUAL);

        assertThat(MembershipDisplayName.commonName(discount.getCarrierBenefitName(), "GS25"))
                .isEqualTo("GS25 멤버십 혜택");
        assertThat(discount.getDisplayBenefitName()).isEqualTo("GS25 멤버십 혜택 · 할인형");
        assertThat(accrual.getDisplayBenefitName()).isEqualTo("GS25 멤버십 혜택 · 적립형");
        assertThat(MembershipDisplayName.commonName("GS25 KT 멤버십", "GS25")).isEqualTo("GS25 멤버십 혜택");
        assertThat(MembershipDisplayName.commonName("GS25 LGU+ 멤버십", "GS25")).isEqualTo("GS25 멤버십 혜택");
        assertThat(MembershipDisplayName.commonName("GS25 여름 할인", "GS25")).isEqualTo("GS25 여름 할인");
        assertThat(MembershipDisplayName.commonName("아웃백 스테이크하우스 KT 멤버십", "아웃백"))
                .isEqualTo("아웃백 스테이크하우스 멤버십 혜택");
        assertThat(MembershipDisplayName.commonName("롯데면세점(온라인) KT 멤버십", "롯데면세점"))
                .isEqualTo("롯데면세점(온라인) 멤버십 혜택");
    }

    private BenefitCarrierPolicy policy(Benefit benefit, String rawName, MembershipOptionType option) {
        return BenefitCarrierPolicy.builder().benefit(benefit).carrier(Carrier.SKT)
                .carrierBenefitName(rawName).membershipOptionType(option).build();
    }
}
