package com.itplace.userapi.ai.rag.index;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.ai.rag.document.BenefitDocument;
import com.itplace.userapi.ai.rag.metadata.BenefitRagMetadataClassifier;
import com.itplace.userapi.ai.rag.metadata.BenefitBusinessTypeLabels;
import com.itplace.userapi.benefit.dto.BenefitConditions;
import com.itplace.userapi.benefit.dto.BenefitFact;
import com.itplace.userapi.benefit.dto.BenefitOffer;
import com.itplace.userapi.benefit.dto.BenefitSourceBlock;
import com.itplace.userapi.benefit.dto.NormalizationIssue;
import com.itplace.userapi.benefit.entity.Benefit;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.CarrierTierBenefit;
import com.itplace.userapi.benefit.entity.enums.BenefitType;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.reference.TestReferenceDataService;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import com.itplace.userapi.partner.entity.Partner;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BenefitRagDocumentBuilderTest {

    @Test
    void koreanBusinessTypeLabelsAreGroundedAndUnknownTypesStayGeneric() {
        assertThat(BenefitBusinessTypeLabels.koreanLabel("CONVENIENCE_STORE")).isEqualTo("편의점");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("EDUCATION")).isEqualTo("교육 학원");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("STUDY_SPACE")).isEqualTo("스터디카페 독서실");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("KIDS_PLAY")).isEqualTo("키즈카페 어린이 놀이시설");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("BEVERAGE_CAFE")).isEqualTo("카페 음료");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("FOOD_RESTAURANT")).isEqualTo("음식점 식당");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("DESSERT")).isEqualTo("디저트");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("MOVIE_THEATER")).isEqualTo("영화관 영화");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("CULTURE")).isEqualTo("문화 공연");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("ATTRACTION")).isEqualTo("놀이공원 관광지");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("WATER_PARK")).isEqualTo("워터파크");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("HOTEL")).isEqualTo("호텔 숙박");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("AUTO_SERVICE")).isEqualTo("자동차 정비");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("COUNSELING")).isEqualTo("상담");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("SHOPPING")).isEqualTo("쇼핑");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("OTHER")).isEqualTo("기타");
        assertThat(BenefitBusinessTypeLabels.koreanLabel("UNRECOGNIZED")).isEqualTo("기타");
        assertThat(BenefitBusinessTypeLabels.koreanLabel(null)).isEqualTo("기타");
    }

    @Test
    void searchableTextSeparatesSimilarBusinessPurposesWithoutInventingOfferTerms() {
        var builder = new BenefitRagDocumentBuilder(new BenefitRagMetadataClassifier());
        var kids = buildSimpleSearchText(builder, "타요키즈카페", "키즈카페");
        var study = buildSimpleSearchText(builder, "초심스터디카페", "교육");
        var cafe = buildSimpleSearchText(builder, "스타벅스", "카페");

        assertThat(kids).contains("제휴처 업종: 키즈카페 어린이 놀이시설").doesNotContain("스터디카페 독서실", "카페 음료");
        assertThat(study).contains("제휴처 업종: 스터디카페 독서실").doesNotContain("키즈카페 어린이 놀이시설", "카페 음료");
        assertThat(cafe).contains("제휴처 업종: 카페 음료").doesNotContain("키즈카페 어린이 놀이시설", "스터디카페 독서실");
        assertThat(kids + study + cafe).doesNotContain("무료", "할인율", "무제한");
    }

    private String buildSimpleSearchText(BenefitRagDocumentBuilder builder, String partnerName, String category) {
        Benefit benefit = Benefit.builder().benefitId(1L).benefitName("제휴 혜택")
                .partner(Partner.builder().partnerId(2L).partnerName(partnerName).category(category).build()).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(3L).benefit(benefit)
                .carrier(Carrier.KT).description("원문 혜택 설명").build();
        return builder.buildPendingDocuments(benefit, List.of(policy), List.of()).get(0).searchableText();
    }

    private BenefitOffer gradeOffer(String key, String title, String grade, String source) {
        return new BenefitOffer(key, title, source, UsageType.ONLINE, List.of(grade), false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(new BenefitFact("REWARD_RATE", new java.math.BigDecimal(grade.equals("KT_VIP") ? "15" : "5"),
                        "PERCENT", null, null, null, null, "EXACT", source, null)));
    }

    private BenefitRagDocumentBuilder structuredBuilder(List<com.itplace.userapi.reference.ReferenceData.Grade> grades) {
        var reference = org.mockito.Mockito.mock(com.itplace.userapi.reference.ReferenceDataService.class);
        org.mockito.Mockito.when(reference.getReferenceData()).thenReturn(new com.itplace.userapi.reference.ReferenceData(
                grades, List.of(), List.of(), List.of()));
        org.mockito.Mockito.lenient().when(reference.isBenefitGrade(Carrier.KT, Grade.KT_VIP)).thenReturn(true);
        org.mockito.Mockito.lenient().when(reference.isBenefitGrade(Carrier.KT, Grade.KT_GENERAL)).thenReturn(true);
        var builder = new BenefitRagDocumentBuilder(new BenefitRagMetadataClassifier());
        ReflectionTestUtils.setField(builder, "benefitOfferMatcher", new com.itplace.userapi.benefit.service.BenefitOfferMatcher(reference));
        ReflectionTestUtils.setField(builder, "referenceDataService", reference);
        return builder;
    }

    private List<com.itplace.userapi.reference.ReferenceData.Grade> ktGrades() {
        return List.of(new com.itplace.userapi.reference.ReferenceData.Grade("KT", "KT_VIP", "VIP", 1, true, true, true, false),
                new com.itplace.userapi.reference.ReferenceData.Grade("KT", "KT_GENERAL", "일반", 2, true, true, true, false));
    }

    @Test
    void sourceOffersWithoutAnyTierRowsProduceGradeScopedDocuments() {
        Benefit benefit = Benefit.builder().benefitId(51L).benefitName("등급별 온라인 적립")
                .partner(Partner.builder().partnerId(52L).partnerName("쇼핑몰").category("쇼핑").build()).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(53L).benefit(benefit)
                .carrier(Carrier.KT).usageType(UsageType.BOTH)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(gradeOffer("vip-rate", "VIP 적립", "KT_VIP", "VIP 15% 적립"),
                                gradeOffer("general-rate", "일반 적립", "KT_GENERAL", "일반 5% 적립")),
                        com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL, null)).build();

        var docs = structuredBuilder(ktGrades()).buildPendingDocuments(benefit, List.of(policy), List.of());

        assertThat(docs).hasSize(2);
        var vip = docs.stream().filter(doc -> "KT_VIP".equals(doc.document().getGrade())).findFirst().orElseThrow();
        var general = docs.stream().filter(doc -> "KT_GENERAL".equals(doc.document().getGrade())).findFirst().orElseThrow();
        assertThat(vip.document().getBenefitId()).isEqualTo("51");
        assertThat(vip.document().getPolicyId()).isEqualTo("53");
        assertThat(vip.document().getTierBenefitId()).isNull();
        assertThat(vip.document().getOfferKey()).isEqualTo("vip-rate");
        assertThat(vip.document().getDocumentId()).contains(":offer:vip-rate:grade:KT_VIP");
        assertThat(vip.searchableText()).contains("VIP 15% 적립", "15% 적립")
                .doesNotContain("일반 5% 적립", "일반 5%");
        assertThat(general.document().getTierBenefitId()).isNull();
        assertThat(general.document().getOfferKey()).isEqualTo("general-rate");
        assertThat(general.searchableText()).contains("일반 5% 적립", "5% 적립")
                .doesNotContain("VIP 15% 적립", "15% 적립");
    }

    @Test
    void canonicalCoverageSuppressesOnlyCoveredGradeOfferDocument() {
        Benefit benefit = Benefit.builder().benefitId(61L).benefitName("등급별 온라인 적립")
                .partner(Partner.builder().partnerId(62L).partnerName("쇼핑몰").category("쇼핑").build()).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(63L).benefit(benefit)
                .carrier(Carrier.KT).usageType(UsageType.BOTH)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(gradeOffer("vip-rate", "VIP 적립", "KT_VIP", "VIP 15% 적립"),
                                gradeOffer("general-rate", "일반 적립", "KT_GENERAL", "일반 5% 적립")),
                        com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL, null)).build();
        CarrierTierBenefit general = CarrierTierBenefit.builder().carrierTierBenefitId(64L)
                .benefitCarrierPolicy(policy).grade(Grade.KT_GENERAL).offerKey("general-rate").context("일반 혜택").build();

        var docs = structuredBuilder(ktGrades()).buildPendingDocuments(benefit, List.of(policy), List.of(general));

        assertThat(docs).hasSize(2);
        var vip = docs.stream().filter(doc -> "vip-rate".equals(doc.document().getOfferKey())).findFirst().orElseThrow();
        var generalDoc = docs.stream().filter(doc -> "64".equals(doc.document().getTierBenefitId())).findFirst().orElseThrow();
        assertThat(vip.document().getGrade()).isEqualTo("KT_VIP");
        assertThat(vip.document().getTierBenefitId()).isNull();
        assertThat(vip.searchableText()).contains("VIP 15% 적립").doesNotContain("일반 5% 적립");
        assertThat(generalDoc.document().getOfferKey()).isEqualTo("general-rate");
        assertThat(generalDoc.searchableText()).contains("일반 5% 적립").doesNotContain("VIP 15% 적립");
    }

    @Test
    void sameGradeTierCoversOnlyItsOfferAndMalformedKeysDoNotCoverOrIndex() {
        Benefit benefit = Benefit.builder().benefitId(71L).benefitName("등급별 제휴 혜택")
                .partner(Partner.builder().partnerId(72L).partnerName("제휴처").category("생활").build()).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(73L).benefit(benefit)
                .carrier(Carrier.KT).usageType(UsageType.BOTH)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(gradeOffer("ticket", "영화표", "KT_VIP", "VIP 영화표 할인"),
                                gradeOffer("snack", "팝콘", "KT_VIP", "VIP 팝콘 할인"),
                                gradeOffer("bad key", "제외", "KT_VIP", "잘못된 키 혜택")),
                        com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL, null)).build();
        CarrierTierBenefit tier = CarrierTierBenefit.builder().carrierTierBenefitId(74L)
                .benefitCarrierPolicy(policy).grade(Grade.KT_VIP).offerKey("ticket").context("영화표 할인").build();
        CarrierTierBenefit malformed = CarrierTierBenefit.builder().carrierTierBenefitId(75L)
                .benefitCarrierPolicy(policy).grade(Grade.KT_VIP).offerKey("bad key").context("잘못된 키 혜택").build();

        var docs = structuredBuilder(ktGrades()).buildPendingDocuments(benefit, List.of(policy), List.of(tier, malformed));

        assertThat(docs).hasSize(2);
        assertThat(docs).anySatisfy(doc -> {
            assertThat(doc.document().getOfferKey()).isEqualTo("ticket");
            assertThat(doc.document().getTierBenefitId()).isEqualTo("74");
        });
        assertThat(docs).anySatisfy(doc -> {
            assertThat(doc.document().getOfferKey()).isEqualTo("snack");
            assertThat(doc.document().getTierBenefitId()).isNull();
            assertThat(doc.searchableText()).contains("VIP 팝콘 할인").doesNotContain("VIP 영화표 할인", "잘못된 키 혜택");
        });
    }

    @Test
    void allGradeOfferFillsOnlyGradesMissingFromItsLinkedTiers() {
        Benefit benefit = Benefit.builder().benefitId(81L).benefitName("전체 등급 영화 혜택")
                .partner(Partner.builder().partnerId(82L).partnerName("영화관").category("영화").build()).build();
        BenefitOffer allGrade = new BenefitOffer("all-movie", "전 등급 영화 할인", "전 등급 영화 할인",
                UsageType.BOTH, List.of(), true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(83L).benefit(benefit)
                .carrier(Carrier.KT).usageType(UsageType.BOTH)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(allGrade), com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL, null)).build();
        CarrierTierBenefit existing = CarrierTierBenefit.builder().carrierTierBenefitId(84L)
                .benefitCarrierPolicy(policy).grade(Grade.KT_VIP).offerKey("all-movie").context("VIP 영화 할인").build();

        var docs = structuredBuilder(ktGrades()).buildPendingDocuments(benefit, List.of(policy), List.of(existing));

        assertThat(docs).hasSize(2);
        assertThat(docs).anySatisfy(doc -> assertThat(doc.document().getTierBenefitId()).isEqualTo("84"));
        assertThat(docs).anySatisfy(doc -> {
            assertThat(doc.document().getOfferKey()).isEqualTo("all-movie");
            assertThat(doc.document().getTierBenefitId()).isNull();
            assertThat(doc.document().getGrade()).isEqualTo("KT_GENERAL");
        });
    }

    @Test
    void explicitOfferGradeDoesNotDependOnReferenceCatalogRows() {
        Benefit benefit = Benefit.builder().benefitId(91L).benefitName("VIP 전용 혜택")
                .partner(Partner.builder().partnerId(92L).partnerName("제휴처").category("생활").build()).build();
        BenefitOffer vipOffer = gradeOffer("vip", "VIP 할인", "KT_VIP", "VIP 할인");
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(93L).benefit(benefit)
                .carrier(Carrier.KT).usageType(UsageType.BOTH)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(vipOffer), com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL, null)).build();
        var reference = org.mockito.Mockito.mock(com.itplace.userapi.reference.ReferenceDataService.class);
        org.mockito.Mockito.when(reference.getReferenceData()).thenReturn(new com.itplace.userapi.reference.ReferenceData(
                List.of(), List.of(), List.of(), List.of()));
        org.mockito.Mockito.when(reference.isBenefitGrade(Carrier.KT, Grade.KT_VIP)).thenReturn(true);
        var builder = new BenefitRagDocumentBuilder(new BenefitRagMetadataClassifier());
        ReflectionTestUtils.setField(builder, "benefitOfferMatcher", new com.itplace.userapi.benefit.service.BenefitOfferMatcher(reference));
        ReflectionTestUtils.setField(builder, "referenceDataService", reference);

        var docs = builder.buildPendingDocuments(benefit, List.of(policy), List.of());

        assertThat(docs).singleElement().satisfies(doc -> {
            assertThat(doc.document().getGrade()).isEqualTo("KT_VIP");
            assertThat(doc.document().getOfferKey()).isEqualTo("vip");
        });
    }

    private static int count(String text, String value) {
        return (text.length() - text.replace(value, "").length()) / value.length();
    }

    @Test
    void partialOffersKeepFactsOnMatchingTierAndNeverIndexUnassignedSourceBlocks() {
        Benefit benefit = Benefit.builder().benefitId(1L).benefitName("쇼핑 혜택")
                .partner(Partner.builder().partnerId(2L).partnerName("쇼핑몰").category("쇼핑").build()).build();
        BenefitOffer vipOffer = new BenefitOffer("vip-online", "VIP 온라인 적립", "VIP 온라인 15% 적립",
                UsageType.ONLINE, List.of("KT_VIP"), false, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("온라인 전용"), "offer/vip", List.of(new BenefitFact("REWARD_RATE",
                new java.math.BigDecimal("15"), "PERCENT", null, null, null, null, "EXACT",
                "15% 적립", "offer/vip/fact")));
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(3L).benefit(benefit)
                .carrier(Carrier.KT).usageType(UsageType.BOTH)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(vipOffer), com.itplace.userapi.benefit.dto.OfferResolution.PARTIAL, null,
                        List.of(new BenefitSourceBlock("section/other", "section", "paragraph", "공통 안내", "GENERAL 현장 조건")),
                        List.of(new NormalizationIssue("UNASSIGNED_SHARED_RULE", "", "혼합 혜택 조건", "section/other"))))
                .build();
        CarrierTierBenefit vip = CarrierTierBenefit.builder().carrierTierBenefitId(4L).benefitCarrierPolicy(policy)
                .grade(Grade.KT_VIP).context("VIP 연 6회 무료").offerKey("vip-online").build();
        CarrierTierBenefit general = CarrierTierBenefit.builder().carrierTierBenefitId(5L).benefitCarrierPolicy(policy)
                .grade(Grade.KT_GENERAL).context("일반 등급 월 1회").build();
        var reference = org.mockito.Mockito.mock(com.itplace.userapi.reference.ReferenceDataService.class);
        org.mockito.Mockito.when(reference.getReferenceData()).thenReturn(new com.itplace.userapi.reference.ReferenceData(
                List.of(new com.itplace.userapi.reference.ReferenceData.Grade("KT", "KT_VIP", "VIP", 1, true, true, true, false),
                        new com.itplace.userapi.reference.ReferenceData.Grade("KT", "KT_GENERAL", "일반", 2, true, true, true, false)),
                List.of(), List.of(), List.of()));
        org.mockito.Mockito.when(reference.isBenefitGrade(Carrier.KT, Grade.KT_VIP)).thenReturn(true);
        var builder = new BenefitRagDocumentBuilder(new BenefitRagMetadataClassifier());
        ReflectionTestUtils.setField(builder, "benefitOfferMatcher", new com.itplace.userapi.benefit.service.BenefitOfferMatcher(reference));
        ReflectionTestUtils.setField(builder, "referenceDataService", reference);

        var documents = builder.buildPendingDocuments(benefit, List.of(policy), List.of(vip, general));

        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).searchableText()).contains("VIP 온라인 15% 적립", "15% 적립", "VIP 연 6회 무료")
                .doesNotContain("GENERAL 현장 조건");
        assertThat(documents.get(0).document().getOfferKey()).isEqualTo("vip-online");
        assertThat(documents.get(1).searchableText()).contains("일반 등급 월 1회")
                .doesNotContain("VIP 온라인 15% 적립", "15% 적립", "GENERAL 현장 조건");
    }


    @Test
    void singleOfferKeepsGradeSpecificRewardAndOnlyScopedConditions() {
        Benefit benefit = Benefit.builder().benefitId(1L).benefitName("영화 할인")
                .partner(Partner.builder().partnerId(2L).partnerName("영화관").category("영화").build()).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(3L).benefit(benefit)
                .carrier(Carrier.SKT).usageType(UsageType.BOTH)
                .conditions(new BenefitConditions(List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(new com.itplace.userapi.benefit.dto.BenefitOffer("movie", "영화 할인", "예매 할인",
                                UsageType.BOTH, List.of("SKT_VIP"), false, List.of("영화표"), List.of(),
                                List.of("월 1회"), List.of(), List.of("특별관 제외"), List.of())),
                        com.itplace.userapi.benefit.dto.OfferResolution.SINGLE, null))
                .build();
        CarrierTierBenefit vip = CarrierTierBenefit.builder().carrierTierBenefitId(4L).benefitCarrierPolicy(policy)
                .grade(Grade.SKT_VIP).context("VIP 연 6회 무료").offerKey("movie").build();
        CarrierTierBenefit gold = CarrierTierBenefit.builder().carrierTierBenefitId(5L).benefitCarrierPolicy(policy)
                .grade(Grade.SKT_GOLD).context("GOLD 15% 할인").build();
        var reference = org.mockito.Mockito.mock(com.itplace.userapi.reference.ReferenceDataService.class);
        org.mockito.Mockito.when(reference.getReferenceData()).thenReturn(new com.itplace.userapi.reference.ReferenceData(
                List.of(new com.itplace.userapi.reference.ReferenceData.Grade("SKT", "SKT_VIP", "VIP", 1, true, true, true, false),
                        new com.itplace.userapi.reference.ReferenceData.Grade("SKT", "SKT_GOLD", "Gold", 2, true, true, true, false)),
                List.of(), List.of(), List.of()));
        org.mockito.Mockito.when(reference.resolveGradeAlias(Carrier.SKT, "SKT_VIP")).thenReturn(null);
        org.mockito.Mockito.when(reference.isBenefitGrade(Carrier.SKT, Grade.SKT_VIP)).thenReturn(true);
        var builder = new BenefitRagDocumentBuilder(new BenefitRagMetadataClassifier());
        ReflectionTestUtils.setField(builder, "benefitOfferMatcher",
                new com.itplace.userapi.benefit.service.BenefitOfferMatcher(reference));
        ReflectionTestUtils.setField(builder, "referenceDataService", reference);

        var documents = builder.buildPendingDocuments(benefit, List.of(policy), List.of(vip, gold));

        assertThat(documents).hasSize(2);
        assertThat(documents.get(0).searchableText()).contains("VIP 연 6회 무료", "영화표", "특별관 제외")
                .doesNotContain("GOLD 15% 할인");
        assertThat(documents.get(1).searchableText()).contains("GOLD 15% 할인")
                .doesNotContain("VIP 연 6회 무료", "영화표", "특별관 제외");
        assertThat(documents.get(0).document().getConditionExclusions()).containsExactly("특별관 제외");
        assertThat(documents.get(0).document().getOfferKey()).isEqualTo("movie");
    }

    @Test
    void embeddingHashDependsOnTextInsteadOfCrawlTimeOrDatabaseId() {
        Benefit benefit = Benefit.builder().benefitId(1L).benefitName("커피")
                .partner(Partner.builder().partnerId(2L).partnerName("카페").build()).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(3L)
                .benefit(benefit).carrier(Carrier.SKT).description("10% 할인").build();
        var builder = new BenefitRagDocumentBuilder(new BenefitRagMetadataClassifier());
        var original = builder.buildPendingDocuments(benefit, List.of(policy), List.of()).get(0).document();
        policy.setLastCrawledAt(LocalDateTime.of(2026, 9, 9, 12, 0));
        policy.setBenefitCarrierPolicyId(4L);
        var refreshed = builder.buildPendingDocuments(benefit, List.of(policy), List.of()).get(0).document();
        assertThat(refreshed.getContentHash()).isEqualTo(original.getContentHash());
        assertThat(refreshed.getDocumentId()).isNotEqualTo(original.getDocumentId());
        assertThat(refreshed.getSourceUpdatedAt()).isNotEqualTo(original.getSourceUpdatedAt());
        policy.setDescription("20% 할인");
        assertThat(builder.buildPendingDocuments(benefit, List.of(policy), List.of()).get(0).document().getContentHash())
                .isNotEqualTo(original.getContentHash());
    }

    @Test
    void buildPendingDocumentsIndexesCarrierPolicyAndTierMetadataSeparately() {
        Benefit benefit = Benefit.builder()
                .benefitId(100L)
                .benefitName("영화 할인")
                .mainCategory(MainCategory.BASIC_BENEFIT)
                .partner(Partner.builder()
                        .partnerId(10L)
                        .partnerName("영화관")
                        .category("영화")
                        .build())
                .active(true)
                .build();
        BenefitCarrierPolicy sktPolicy = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(200L)
                .benefit(benefit)
                .carrier(Carrier.SKT)
                .active(true)
                .carrierBenefitName("T멤버십 영화 할인")
                .description("영화 예매 할인")
                .manual("현장/온라인 이용")
                .sourceLimitText("월 1회")
                .conditions(new BenefitConditions(List.of("일반 영화 예매"), List.of("월 1회"),
                        List.of("월 1회"), List.of("앱 결제"), List.of("특별관 제외")))
                .sourceOptionLabel("VIP 옵션")
                .membershipOptionType(com.itplace.userapi.benefit.entity.enums.MembershipOptionType.DISCOUNT)
                .usageType(UsageType.BOTH)
                .type(BenefitType.DISCOUNT)
                .sourceKey("skt-movie")
                .sourceEvidenceId(400L)
                .sourceUrl("https://example.com/skt")
                .sourceCategory("culture")
                .lastCrawledAt(LocalDateTime.of(2026, 5, 13, 1, 2))
                .build();
        CarrierTierBenefit vipTier = CarrierTierBenefit.builder()
                .carrierTierBenefitId(300L)
                .benefitCarrierPolicy(sktPolicy)
                .grade(Grade.SKT_VIP)
                .context("VIP 연 6회 무료")
                .discountValue(100)
                .isAll(false)
                .build();


        BenefitRagDocumentBuilder builder = new BenefitRagDocumentBuilder(
                new BenefitRagMetadataClassifier()
        );

        List<BenefitRagDocumentBuilder.PendingBenefitDocument> pendingDocuments = builder.buildPendingDocuments(benefit, List.of(sktPolicy), List.of(vipTier));

        assertThat(pendingDocuments)
                .singleElement()
                .satisfies(pending -> {
                    assertThat(pending.searchableText()).contains("SKT_VIP", "영화 예매 할인", "VIP 연 6회 무료");
                    assertThat(pending.searchableText()).contains("통신사: SKT", "대상 등급: SKT_VIP",
                            "이용 채널: BOTH", "혜택 세부 내용: 현장/온라인 이용",
                            "대상 및 한도: 일반 영화 예매", "대상 및 한도: 월 1회");
                    assertThat(pending.searchableText()).doesNotContain("원문 옵션명", "이용 방법 원문");
                    assertThat(pending.searchableText()).contains("앱 결제", "특별관 제외");
                    assertThat(count(pending.searchableText(), "대상 및 한도: 월 1회")).isEqualTo(1);
                    assertThat(count(pending.searchableText(), "VIP 연 6회 무료")).isEqualTo(1);
                    BenefitDocument document = pending.withEmbedding(List.of(0.1f, 0.2f));
                    assertThat(document.getDocumentId()).isEqualTo("benefit:100:policy:200:tier:300");
                    assertThat(document.getBenefitId()).isEqualTo("100");
                    assertThat(document.getPolicyId()).isEqualTo("200");
                    assertThat(document.getSourceEvidenceId()).isEqualTo(400L);
                    assertThat(document.getTierBenefitId()).isEqualTo("300");
                    assertThat(document.getCarrier()).isEqualTo("SKT");
                    assertThat(document.getGrade()).isEqualTo("SKT_VIP");
                    assertThat(document.getIsAllGrade()).isFalse();
                    assertThat(document.getUsageType()).isEqualTo("BOTH");
                    assertThat(document.getBenefitType()).isEqualTo("DISCOUNT");
                    assertThat(document.getTierContext()).isEqualTo("VIP 연 6회 무료");
                    assertThat(document.getManual()).isEqualTo("현장/온라인 이용");
                    assertThat(document.getConditionUsage()).containsExactly("앱 결제");
                    assertThat(document.getConditionExclusions()).containsExactly("특별관 제외");
                    assertThat(document.getEmbeddingVersion()).isEqualTo(BenefitRagDocumentBuilder.EMBEDDING_VERSION);
                    assertThat(document.getContentHash()).isNotBlank();
                    assertThat(document.getSyncStatus()).isEqualTo("ACTIVE");
                    assertThat(document.getEmbedding()).containsExactly(0.1f, 0.2f);
                    assertThat(document.getBusinessType()).isEqualTo("MOVIE_THEATER");
                    assertThat(document.getUseCases()).isEmpty();
                    assertThat(document.getNegativeUseCases()).isEmpty();
                    assertThat(document.getTags()).containsExactly("MOVIE_THEATER");
                });
    }

    @Test
    void missingTierIsNotIndexedAsAnAllGradeOffer() {
        Benefit benefit = Benefit.builder().benefitId(501L).benefitName("멤버십 혜택")
                .partner(Partner.builder().partnerId(502L).partnerName("제휴처").category("영화").build()).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(503L).benefit(benefit)
                .carrier(Carrier.KT).active(true).build();

        var document = new BenefitRagDocumentBuilder(new BenefitRagMetadataClassifier())
                .buildPendingDocuments(benefit, List.of(policy), List.of()).get(0).document();

        assertThat(document.getIsAllGrade()).isFalse();
        assertThat(document.getGrade()).isNull();
    }

    @Test
    void diamondSourceGradeIsSearchableByKoreanLabelAndRetainsEnumCode() {
        Benefit benefit = Benefit.builder().benefitId(601L).benefitName("다이아몬드 혜택")
                .partner(Partner.builder().partnerId(602L).partnerName("제휴처").category("영화").build()).build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder().benefitCarrierPolicyId(603L).benefit(benefit)
                .carrier(Carrier.LGU).active(true).build();
        CarrierTierBenefit tier = CarrierTierBenefit.builder().carrierTierBenefitId(604L)
                .benefitCarrierPolicy(policy).grade(Grade.DIAMOND).context("다이아몬드 전용 할인").isAll(false).build();

        BenefitRagDocumentBuilder builder = new BenefitRagDocumentBuilder(new BenefitRagMetadataClassifier());
        ReflectionTestUtils.setField(builder, "referenceDataService", new TestReferenceDataService());
        var pending = builder
                .buildPendingDocuments(benefit, List.of(policy), List.of(tier)).get(0);

        assertThat(pending.searchableText()).contains("대상 등급: 다이아몬드");
        assertThat(pending.document().getGrade()).isEqualTo("DIAMOND");
    }

    @Test
    void buildPendingDocumentsStoresOnlineAndOfflineContextsSeparately() {
        Benefit benefit = Benefit.builder()
                .benefitId(101L)
                .benefitName("피자 할인")
                .mainCategory(MainCategory.BASIC_BENEFIT)
                .partner(Partner.builder()
                        .partnerId(11L)
                        .partnerName("미스터피자")
                        .category("피자")
                        .build())
                .active(true)
                .build();
        BenefitCarrierPolicy policy = BenefitCarrierPolicy.builder()
                .benefitCarrierPolicyId(201L)
                .benefit(benefit)
                .carrier(Carrier.SKT)
                .active(true)
                .carrierBenefitName("T멤버십 피자 할인")
                .description("피자 할인")
                .manual("멤버십 제시")
                .usageType(UsageType.BOTH)
                .type(BenefitType.DISCOUNT)
                .build();
        CarrierTierBenefit tier = CarrierTierBenefit.builder()
                .carrierTierBenefitId(301L)
                .benefitCarrierPolicy(policy)
                .grade(Grade.SKT_VIP)
                .context("온라인: 방문포장 25% 할인 / 오프라인: 매장 25% 할인")
                .isAll(false)
                .build();


        BenefitRagDocumentBuilder builder = new BenefitRagDocumentBuilder(
                new BenefitRagMetadataClassifier()
        );

        List<BenefitRagDocumentBuilder.PendingBenefitDocument> pendingDocuments = builder.buildPendingDocuments(benefit, List.of(policy), List.of(tier));

        assertThat(pendingDocuments)
                .singleElement()
                .satisfies(pending -> {
                    assertThat(pending.searchableText()).contains("등급별 혜택: 온라인: 방문포장 25% 할인 / 오프라인: 매장 25% 할인");
                    assertThat(count(pending.searchableText(), "온라인: 방문포장 25% 할인")).isEqualTo(1);
                    BenefitDocument document = pending.document();
                    assertThat(document.getOnlineContext()).isEqualTo("방문포장 25% 할인");
                    assertThat(document.getOfflineContext()).isEqualTo("매장 25% 할인");
                });
    }
}
