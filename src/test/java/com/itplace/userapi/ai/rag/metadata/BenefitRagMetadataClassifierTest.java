package com.itplace.userapi.ai.rag.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BenefitRagMetadataClassifierTest {
    private final BenefitRagMetadataClassifier classifier = new BenefitRagMetadataClassifier();

    @Test
    void recognizesConvenienceChainsEvenWhenCategoryIsBlankOrGeneric() {
        assertThat(classify("GS25", "", "매장 구매 할인").businessType()).isEqualTo("CONVENIENCE_STORE");
        assertThat(classify("CU", "", "할인 혜택").businessType()).isEqualTo("CONVENIENCE_STORE");
        assertThat(classify("세븐일레븐", "", "할인 혜택").businessType()).isEqualTo("CONVENIENCE_STORE");
        assertThat(classify("이마트24", "생활/편의", "할인 혜택").businessType()).isEqualTo("CONVENIENCE_STORE");
        assertThat(classify("이마트", "", "할인 혜택").businessType()).isNotEqualTo("CONVENIENCE_STORE");
        assertThat(classify("스피드메이트", "생활/편의", "정비 혜택").businessType()).isNotEqualTo("CONVENIENCE_STORE");
        assertThat(classify("다락", "생활/편의", "공간 혜택").businessType()).isNotEqualTo("CONVENIENCE_STORE");
    }

    @Test
    void deliveryBenefitIsNotClassifiedAsGeneralConvenienceStoreOffer() {
        assertThat(classifier.classify("GS25", "생활/편의", "", "편의점택배 배송 할인", "", "", "").businessType())
                .isNotEqualTo("CONVENIENCE_STORE");
    }

    @Test
    void explicitConvenienceCategoryWinsOverCafeWordsInDescription() {
        assertThat(classify("제휴처", "편의점", "커피 할인").businessType())
                .isEqualTo("CONVENIENCE_STORE");
        assertThat(classify("제휴처", "생활/편의", "커피 할인").businessType())
                .isNotEqualTo("CONVENIENCE_STORE");
    }

    @Test
    void distinguishesKidsStudyAndEducationFromGenericCafeAndManualWords() {
        assertThat(classifier.classify("타요키즈카페", "", "", "할인", "", "", "").businessType())
                .isEqualTo("KIDS_PLAY");
        assertThat(classifier.classify("초심스터디카페", "교육", "", "할인", "", "", "").businessType())
                .isEqualTo("STUDY_SPACE");
        assertThat(classifier.classify("파고다", "교육", "", "할인", "", "", "").businessType())
                .isEqualTo("EDUCATION");
        assertThat(classifier.classify("제휴처", "생활/편의", "", "생활 혜택", "", "카페 직원에게 쿠폰을 제시하세요", "").businessType())
                .isEqualTo("OTHER");
    }

    @Test
    void convenienceChainParcelAndSubscriptionBenefitsAreNotStorePurchaseOffers() {
        assertThat(classifier.classify("GS25", "생활/편의", "", "GS25 편의점택배 할인", "", "", "").businessType())
                .isEqualTo("OTHER");
        assertThat(classifier.classify("CU", "", "", "구독료 캐시백", "", "구독 서비스 안내", "").businessType())
                .isEqualTo("OTHER");
    }

    @Test
    void doesNotInventIntentOrExclusionTags() {
        var metadata = classify("카페", "카페", "커피 할인");
        assertThat(metadata.useCases()).isEmpty();
        assertThat(metadata.negativeUseCases()).isEmpty();
        assertThat(metadata.tags()).containsExactly("BEVERAGE_CAFE");
    }

    @Test
    void usesOfficialPolicyCategoryWhenPartnerCategoryIsMissing() {
        assertThat(classifier.classify("제휴처", "", "", "혜택", "", "", "", "교육").businessType())
                .isEqualTo("EDUCATION");
        assertThat(classifier.classify("제휴처", "", "", "혜택", "", "", "", "푸드").businessType())
                .isEqualTo("FOOD_RESTAURANT");
        assertThat(classifier.classify("스와로브스키", "", "", "카페 스와로브스키 LGU+ 멤버십", "드립백&커피캡슐 할인", "", "", "푸드").businessType())
                .isEqualTo("BEVERAGE_CAFE");
    }

    @Test
    void specificPartnerAndOfferPurposeTakePrecedenceOverBroadOfficialCategory() {
        assertThat(classifier.classify("GS25", "", "", "GS25 할인", "", "", "", "쇼핑").businessType())
                .isEqualTo("CONVENIENCE_STORE");
        assertThat(classifier.classify("제휴처", "", "", "디저트 할인", "", "", "", "푸드").businessType())
                .isEqualTo("DESSERT");
    }

    @Test
    void storePurchasePurposeIsNotOverturnedByExcludedParcelServiceInManual() {
        String manual = "할인 또는 적립 혜택을 받기 위해 바코드를 제시해야 합니다.\n"
                + "할인 제외 서비스: 매장 서비스 전체 (택배, 복권, 인쇄·복사)";
        assertThat(classifier.classify("CU", "", "", "CU 할인형", "1천 원당 100원 할인", manual, "")
                .businessType()).isEqualTo("CONVENIENCE_STORE");
        assertThat(classifier.classify("GS25", "", "", "GS25 편의점택배 할인", "국내택배 10% 할인", manual, "")
                .businessType()).isEqualTo("OTHER");
    }

    @Test
    void recognizesStarbucksAsBeverageChainBeforeBroadCategories() {
        assertThat(classifier.classify("스타벅스", "푸드", "", "스타벅스 KT 멤버십", "[상시] 사이즈업", "", "", "")
                .businessType()).isEqualTo("BEVERAGE_CAFE");
        assertThat(classifier.classify("스타벅스", "", "", "스타벅스 VIP콕", "VVIP 아메리카노 1잔 무료", "", "", "VIP콕")
                .businessType()).isEqualTo("BEVERAGE_CAFE");
    }

    @Test
    void usesExplicitPositiveDescriptionPurposeAndIgnoresExclusionLines() {
        assertThat(classifier.classify("파고다", "", "", "파고다 할인형",
                "① 파고다어학원 20% 할인 ② 파고다인강 10% 할인", "", "", "").businessType())
                .isEqualTo("EDUCATION");
        assertThat(classifier.classify("제휴처", "", "", "멤버십 혜택",
                "※ 어학원 할인 적용 불가", "", "", "").businessType())
                .isEqualTo("OTHER");
    }

    @Test
    void classifiesOnlyExplicitDessertAndTraditionalTeaCafeDescriptionFacts() {
        assertThat(classifier.classify("까레몽 계산점", "", "", "멤버십 혜택",
                "마들렌이 맛있는 디저트 카페", "", "", "인천").businessType()).isEqualTo("DESSERT");
        assertThat(classifier.classify("하베스트과자점", "", "", "멤버십 혜택",
                "달콤한 디저트가 있는 베이커리", "", "", "인천").businessType()).isEqualTo("DESSERT");
        assertThat(classifier.classify("다락", "생활/편의", "", "우리동네 멤버십",
                "다양한 전통차와 편안함을 주는 카페", "매장 방문 시 직원에게 멤버십 카드 제시", "", "인천").businessType())
                .isEqualTo("BEVERAGE_CAFE");
    }

    @Test
    void creditCardSpendCategoryListsRemainOtherInsteadOfBeingClassifiedAsDessert() {
        String lifeCardDescription = "생활친화 업종(편의점, 제과점, 커피 전문점, 패스트푸드 등) 적립 대상 가맹점";
        assertThat(classifier.classify("T 멤버십 라이프 신한카드", "", "", "T 멤버십 라이프 신한카드 할인형",
                lifeCardDescription, "포인트 추가 적립", "", "").businessType()).isEqualTo("OTHER");
        assertThat(classifier.classify("T 멤버십 라이프 신한카드", "", "", "T 멤버십 라이프 신한카드 적립형",
                lifeCardDescription, "포인트 추가 적립", "", "").businessType()).isEqualTo("OTHER");

        String universeCardDescription = "생활 영역 결제 금액의 5~10% T 플러스포인트 적립 (쇼핑/편의점/제과/커피/이미용 업종)\n"
                + "쇼핑 외 영역 10% : 면세점, 편의점, 제과점, 커피전문점, 이미용 업종";
        assertThat(classifier.classify("T 우주 신한카드", "", "", "T 우주 신한카드 할인형",
                universeCardDescription, "T 우주패스 구독료 캐시백", "", "").businessType()).isEqualTo("OTHER");
        assertThat(classifier.classify("T 우주 신한카드", "", "", "T 우주 신한카드 적립형",
                universeCardDescription, "T 우주패스 구독료 캐시백", "", "").businessType()).isEqualTo("OTHER");
    }

    @Test
    void multiMerchantDescriptionListsDoNotBecomeDessertClassification() {
        assertThat(classifier.classify("제휴처", "", "", "멤버십 혜택",
                "적립 대상 가맹점: 편의점, 제과점, 커피전문점", "", "", "").businessType())
                .isEqualTo("OTHER");
    }

    @Test
    void descriptionClassificationDoesNotPromoteStorageSubscriptionOrUnsupportedMediaFacts() {
        assertThat(classifier.classify("다락", "생활/편의", "", "미니창고 다락 할인형",
                "월 이용금액 1개월 무료+35% 할인", "쿠폰 다운로드 후 신규 계약", "", "생활/편의")
                .businessType()).isEqualTo("OTHER");
        assertThat(classifier.classify("FLO", "", "", "FLO 할인형",
                "월정액 30% 할인", "이용권 정기결제 시 할인", "", "")
                .businessType()).isEqualTo("OTHER");
    }

    @Test
    void ignoresCafeExclusionAndInstructionsAndKeepsStudyAndKidsCafePrecedence() {
        assertThat(classifier.classify("제휴처", "", "", "멤버십 혜택",
                "카페 할인 제외", "", "", "").businessType()).isEqualTo("OTHER");
        assertThat(classifier.classify("제휴처", "", "", "멤버십 혜택",
                "카페 이용 안내: 쿠폰 다운로드 후 사용", "", "", "").businessType()).isEqualTo("OTHER");
        assertThat(classifier.classify("제휴처", "", "", "멤버십 혜택",
                "카페 할인 혜택", "", "", "").businessType()).isEqualTo("OTHER");
        assertThat(classifier.classify("제휴처", "", "", "멤버십 혜택",
                "전통차 카페 이용 안내 및 쿠폰 다운로드", "", "", "").businessType()).isEqualTo("OTHER");
        assertThat(classifier.classify("제휴처", "", "", "멤버십 혜택",
                "스터디카페 커피 판매", "", "", "").businessType()).isEqualTo("STUDY_SPACE");
        assertThat(classifier.classify("제휴처", "", "", "멤버십 혜택",
                "키즈카페 이용 할인", "", "", "").businessType()).isEqualTo("KIDS_PLAY");
    }

    @Test
    void recognizesCineQAsMovieTheaterDespiteCultureCategory() {
        assertThat(classifier.classify("씨네Q", "문화/여가", "", "씨네Q 할인형",
                "일반관 관람권 월 8장", "", "", "문화/여가").businessType())
                .isEqualTo("MOVIE_THEATER");
    }

    @Test
    void classifiesAuditedExactPartnerNamesFromSourceEvidence() {
        // Representative sourceEvidenceId anchors: 공차 3440, 폴바셋 3448,
        // 아웃백 3434, 피자알볼로 1192, 텐바이텐 1233, 워커힐 3542,
        // 추억의 국민학교 떡볶이 1309, 달콤이네 2015.
        // Remaining canonical names were reviewed in the active source-audit snapshot.
        BenefitRagMetadataClassifier.AUDITED_PARTNER_TYPES.forEach((partner, type) ->
                assertThat(classifier.classify(partner, "", "", "혜택", "", "", "")
                        .businessType()).as(partner).isEqualTo(type));
    }

    @Test
    void exactNameMappingsDoNotMatchLookalikesOrOverrideFinancialCardGuard() {
        assertThat(classifier.classify("공차 클론", "", "", "혜택", "", "", "").businessType())
                .isEqualTo("OTHER");
        assertThat(classifier.classify("아웃백 유사", "", "", "혜택", "", "", "").businessType())
                .isEqualTo("OTHER");
        // Online packaged-food brands and a butcher's shop do not establish restaurant service.
        assertThat(classifier.classify("허닭", "", "", "할인", "홈페이지 상품 구매 쿠폰", "", "").businessType())
                .isEqualTo("OTHER");
        assertThat(classifier.classify("위미트", "", "", "할인", "모든 상품 온라인 구매 할인", "", "").businessType())
                .isEqualTo("OTHER");
        assertThat(classifier.classify("바른정육점", "", "", "할인", "제품 구입 할인", "", "").businessType())
                .isEqualTo("OTHER");
        assertThat(classifier.classify("T 멤버십 라이프 신한카드", "", "",
                "T 멤버십 라이프 신한카드 적립형", "커피전문점 포함 가맹점 적립", "포인트 적립", "")
                .businessType()).isEqualTo("OTHER");
        assertThat(classifier.classify("GS25편의점택배", "", "", "택배 배송 할인", "", "", "")
                .businessType()).isEqualTo("OTHER");
    }

    @Test
    void positiveAutoWaterAndHotelFactsCanResolveGenericPartner() {
        assertThat(classifier.classify("제휴처", "", "", "혜택",
                "엔진오일 교환 할인", "", "").businessType()).isEqualTo("AUTO_SERVICE");
        assertThat(classifier.classify("제휴처", "", "", "혜택",
                "워터파크 입장권 할인", "", "").businessType()).isEqualTo("WATER_PARK");
        assertThat(classifier.classify("제휴처", "", "", "혜택",
                "호텔 객실 숙박 할인", "", "").businessType()).isEqualTo("HOTEL");
        assertThat(classifier.classify("제휴처", "", "", "혜택",
                "자동차정비 할인 적용 불가", "", "").businessType()).isEqualTo("OTHER");
    }

    @Test
    void hotelPartnerIdentityDoesNotOverrideSourceOfferScope() {
        // Novotel source 1316 conditions the free breakfast on a room reservation.
        assertThat(classifier.classify("노보텔 앰배서더 동대문", "", "", "조식 혜택",
                "객실 예약 시 조식 2인 무료", "", "").businessType()).isEqualTo("HOTEL");
        // Walkerhill source 3542 is only a food/beverage discount at named restaurants.
        assertThat(classifier.classify("워커힐호텔앤리조트", "", "", "식음료 할인",
                "식음료 10% 할인, 제휴 혜택 적용 매장: 온달, 금룡, 더뷔페", "", "").businessType())
                .isEqualTo("FOOD_RESTAURANT");
        // Gonjiam source 1677 mixes a membership package and lounge drinks without a clear lodging fact.
        assertThat(classifier.classify("곤지암리조트", "", "", "멤버십 패키지",
                "멤버십 패키지 10% 할인 및 카페 라운지 음료 쿠폰 2매", "", "").businessType())
                .isEqualTo("OTHER");
    }

    @Test
    void exactPartnerLabelsFollowObservedFoodAndRetailEvidence() {
        // Source 1309 is a shipped online food product with a purchase minimum and shipping charge.
        assertThat(classifier.classify("추억의 국민학교 떡볶이", "", "", "할인 쿠폰",
                "온라인 주문 25,000원 이상 구매 시 배송비", "", "").businessType()).isEqualTo("SHOPPING");
        // Source 2015 describes a bakery selling sandwiches and macarons.
        assertThat(classifier.classify("달콤이네", "", "", "매장 할인",
                "샌드위치와 마카롱이 맛있는 도시락 빵집", "", "").businessType()).isEqualTo("DESSERT");
    }

    @Test
    void exactCounselingProviderOverridesBroadEducationCategory() {
        // Source 1719: LGU+ benefit is an initial counseling discount; 3729 is counseling center service.
        assertThat(classifier.classify("허그맘허그인", "교육", "", "허그맘허그인 LGU+ 멤버십",
                "초기상담 15% 할인", "", "").businessType()).isEqualTo("COUNSELING");
        assertThat(classifier.classify("허그맘허그인", "교육", "", "허그맘허그인 적립형",
                "전 서비스 10% 적립", "", "").businessType()).isEqualTo("COUNSELING");
        assertThat(classifier.classify("제휴처", "", "", "고객상담 할인",
                "고객 상담센터 문의 할인", "", "").businessType()).isEqualTo("OTHER");
    }

    private BenefitRagMetadata classify(String partner, String category, String description) {
        return classifier.classify(partner, category, "", "할인 혜택", description, "", "");
    }
}
