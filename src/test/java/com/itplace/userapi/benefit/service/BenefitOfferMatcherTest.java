package com.itplace.userapi.benefit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.OfferResolution;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.reference.ReferenceDataService;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenefitOfferMatcherTest {
    private final ReferenceDataService reference = mock(ReferenceDataService.class);
    private final BenefitOfferMatcher matcher = new BenefitOfferMatcher(reference);

    @Test
    void channelAndCanonicalGradeKeepDistinctOffersSeparate() {
        BenefitOffer onlineVip = offer("ticket-online", UsageType.ONLINE, List.of("KT_VIP"), false);
        BenefitOffer offlineGeneral = offer("snack-offline", UsageType.OFFLINE, List.of("KT_GENERAL"), false);
        BenefitConditions conditions = new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(onlineVip, offlineGeneral), OfferResolution.SCOPED, null);
        when(reference.isBenefitGrade(Carrier.KT, Grade.KT_VIP)).thenReturn(true);
        when(reference.isBenefitGrade(Carrier.KT, Grade.KT_GENERAL)).thenReturn(true);

        assertThat(matcher.scope(conditions, UsageType.OFFLINE, Carrier.KT, List.of(Grade.KT_VIP)).offers()).isEmpty();
        assertThat(matcher.scope(conditions, UsageType.ONLINE, Carrier.KT, List.of(Grade.KT_VIP)).offers())
                .extracting(BenefitOffer::key).containsExactly("ticket-online");
        assertThat(matcher.scope(conditions, UsageType.OFFLINE, Carrier.KT, List.of(Grade.KT_GENERAL)).offers())
                .extracting(BenefitOffer::key).containsExactly("snack-offline");
    }

    @Test
    void offerAliasesUseReferenceCatalogAndUnknownGradeDoesNotMatch() {
        BenefitConditions conditions = new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(offer("vip-alias", UsageType.ONLINE, List.of("VIP"), false),
                        offer("unknown", UsageType.OFFLINE, List.of(), null)), OfferResolution.SCOPED, null);
        when(reference.resolveGradeAlias(Carrier.LGU, "VIP")).thenReturn(Grade.VIP);
        when(reference.isBenefitGrade(Carrier.LGU, Grade.VIP)).thenReturn(true);

        assertThat(matcher.scope(conditions, UsageType.ONLINE, Carrier.LGU, List.of(Grade.VIP, Grade.VIP콕)).offers())
                .extracting(BenefitOffer::key).containsExactly("vip-alias");
        assertThat(matcher.scope(conditions, UsageType.OFFLINE, Carrier.LGU, List.of(Grade.VIP, Grade.VIP콕)).offers()).isEmpty();
    }

    @Test
    void unresolvedOffersAreNotPresentedAsAtomicOffers() {
        BenefitConditions conditions = new BenefitConditions(List.of("원문 조건"), List.of(), List.of(), List.of(), List.of(),
                List.of(offer("guess", UsageType.BOTH, List.of("KT_VIP"), true)), OfferResolution.UNRESOLVED, null);

        assertThat(matcher.scope(conditions, UsageType.OFFLINE, Carrier.KT, List.of(Grade.KT_VIP)).offers()).isEmpty();
    }

    private BenefitOffer offer(String key, UsageType usage, List<String> grades, Boolean allGrades) {
        return new BenefitOffer(key, key, key, usage, grades, allGrades,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
