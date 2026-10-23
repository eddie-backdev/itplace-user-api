package com.itplace.userapi.benefit.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenefitConditionsPublicViewTest {
    @Test
    void publicViewKeepsOnlyOfferChannelEvidenceAndRemovesInternalSourceLocations() {
        BenefitOffer selected = new BenefitOffer("vip", "VIP benefit", "VIP source", UsageType.ONLINE,
                List.of("KT_VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("VIP online"), "html/body/vip", List.of(new BenefitFact("REWARD_RATE",
                new java.math.BigDecimal("15"), "PERCENT", null, null, null, null, "EXACT", "15% 적립", "html/body/vip")));
        BenefitOffer other = new BenefitOffer("general", "General benefit", "General source", UsageType.OFFLINE,
                List.of("KT_GENERAL"), false, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("General offline"), "html/body/general", List.of());
        BenefitConditions conditions = new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(selected), OfferResolution.PARTIAL,
                new ChannelDecision("v1", "internal reason", List.of("VIP online", "General offline"), List.of("parent exclusion")),
                List.of(new BenefitSourceBlock("html/body", null, "paragraph", "mixed", "unassigned text")),
                List.of(new NormalizationIssue("UNASSIGNED_SHARED_RULE", "raw reason", "source fragment", "html/body"),
                        new NormalizationIssue("UNASSIGNED_SHARED_RULE", "another reason", "another fragment", "html/body/2")));

        BenefitConditions result = conditions.publicView();

        assertThat(result.offers()).singleElement().satisfies(offer -> {
            assertThat(offer.sourcePath()).isNull();
            assertThat(offer.sourceText()).isEqualTo("VIP source");
            assertThat(offer.facts().get(0).sourcePath()).isNull();
        });
        assertThat(result.sourceBlocks()).isEmpty();
        assertThat(result.normalizationIssues()).extracting(NormalizationIssue::sourceText)
                .containsExactly("source fragment", "another fragment");
        assertThat(result.normalizationIssues()).allSatisfy(issue -> {
            assertThat(issue.code()).isEqualTo("UNASSIGNED_SHARED_RULE");
            assertThat(issue.message()).isNull();
            assertThat(issue.sourcePath()).isNull();
        });
        assertThat(result.channelDecision().reason()).isNull();
        assertThat(result.channelDecision().evidence()).containsExactly("VIP online");
        assertThat(result.channelDecision().exclusions()).isEmpty();
    }

    @Test
    void singlePublicOfferDoesNotExposeMixedGradeSourceText() {
        BenefitConditions conditions = new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new BenefitOffer("common", "멤버십", "VIP 15% / GENERAL 5%", UsageType.BOTH,
                        List.of(), true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of())),
                OfferResolution.SINGLE, null);

        assertThat(conditions.publicView().offers().get(0).sourceText()).isNull();
    }
}
