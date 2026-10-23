package com.itplace.userapi.ai.rag.metadata;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class BenefitRagMetadataClassifier {
    private static final List<String> KNOWN_BEVERAGE_CHAINS = List.of("스타벅스", "starbucks");
    // Exact names audited against active source evidence (IDs noted in classifier tests).
    static final Map<String, String> AUDITED_PARTNER_TYPES = normalizedPartnerTypes(Map.ofEntries(
            Map.entry("공차", "BEVERAGE_CAFE"), Map.entry("폴바셋", "BEVERAGE_CAFE"),
            Map.entry("메가MGC커피", "BEVERAGE_CAFE"), Map.entry("더벤티", "BEVERAGE_CAFE"),
            Map.entry("던킨", "DESSERT"), Map.entry("드롭탑", "BEVERAGE_CAFE"),
            Map.entry("파스쿠찌", "BEVERAGE_CAFE"), Map.entry("엔제리너스", "BEVERAGE_CAFE"),
            Map.entry("달콤", "BEVERAGE_CAFE"), Map.entry("아티제", "BEVERAGE_CAFE"),
            Map.entry("블루보틀", "BEVERAGE_CAFE"), Map.entry("달콤이네", "DESSERT"),
            Map.entry("피자알볼로", "FOOD_RESTAURANT"), Map.entry("아웃백", "FOOD_RESTAURANT"),
            Map.entry("롯데리아", "FOOD_RESTAURANT"), Map.entry("샐러디", "FOOD_RESTAURANT"),
            Map.entry("포케올데이", "FOOD_RESTAURANT"), Map.entry("멕시카나", "FOOD_RESTAURANT"),
            Map.entry("bhc", "FOOD_RESTAURANT"), Map.entry("퀴즈노스", "FOOD_RESTAURANT"),
            Map.entry("추억의 국민학교 떡볶이", "SHOPPING"), Map.entry("사보텐", "FOOD_RESTAURANT"),
            Map.entry("크래버대게나라", "FOOD_RESTAURANT"), Map.entry("타코벨", "FOOD_RESTAURANT"),
            Map.entry("도원스타일", "FOOD_RESTAURANT"), Map.entry("히바린", "FOOD_RESTAURANT"),
            Map.entry("경리단578", "FOOD_RESTAURANT"),
            Map.entry("텐바이텐", "SHOPPING"), Map.entry("11번가", "SHOPPING"),
            Map.entry("SK스토아", "SHOPPING"), Map.entry("KT알파 쇼핑(구. K쇼핑)", "SHOPPING"),
            Map.entry("이랜드몰", "SHOPPING"), Map.entry("후디스몰", "SHOPPING"),
            Map.entry("제주관광공사 인터넷면세점", "SHOPPING"), Map.entry("롯데면세점 동경긴자점", "SHOPPING"),
            Map.entry("동원몰", "SHOPPING"), Map.entry("후지필름몰", "SHOPPING"),
            Map.entry("온누리시장(몰)", "SHOPPING"), Map.entry("모던하우스", "SHOPPING"),
            Map.entry("스피드메이트", "AUTO_SERVICE"), Map.entry("오토오아시스", "AUTO_SERVICE"),
            Map.entry("B1불스원카케어", "AUTO_SERVICE"), Map.entry("오토카지", "AUTO_SERVICE"),
            Map.entry("설악워터피아", "WATER_PARK"), Map.entry("파라다이스스파도고", "WATER_PARK"),
            Map.entry("파라다이스시티 씨메르", "WATER_PARK"),
            Map.entry("워커힐호텔앤리조트", "FOOD_RESTAURANT"), Map.entry("노보텔 앰배서더 동대문", "HOTEL"),
            Map.entry("그래비티 조선 서울 판교", "HOTEL"), Map.entry("한화리조트", "HOTEL"),
            Map.entry("에듀윌", "EDUCATION"), Map.entry("YBM 전화화상", "EDUCATION"),
            Map.entry("패스트캠퍼스", "EDUCATION"), Map.entry("에어클래스", "EDUCATION"),
            Map.entry("AICE", "EDUCATION"), Map.entry("코드모스", "EDUCATION"),
            Map.entry("키자니아", "KIDS_PLAY"), Map.entry("티니핑월드 인 판교", "KIDS_PLAY"),
            Map.entry("원더파크/원더빌리지", "KIDS_PLAY"),
            Map.entry("SEA LIFE 부산아쿠아리움", "ATTRACTION"), Map.entry("로봇랜드", "ATTRACTION"),
            Map.entry("여수 유월드", "ATTRACTION"), Map.entry("제주신화월드", "ATTRACTION"),
            Map.entry("파라다이스시티 원더박스", "ATTRACTION"), Map.entry("네이처스케이프 플러스", "ATTRACTION"),
            Map.entry("아르떼뮤지엄", "CULTURE"), Map.entry("르 스페이스", "CULTURE"),
            Map.entry("KT멤버십 공연예매", "CULTURE")));

    public BenefitRagMetadata classify(String partnerName,
                                       String category,
                                       String mainCategory,
                                       String benefitName,
                                       String description,
                                       String manual,
                                       String context) {
        return classify(partnerName, category, mainCategory, benefitName, description, manual, context, null);
    }

    public BenefitRagMetadata classify(String partnerName,
                                       String category,
                                       String mainCategory,
                                       String benefitName,
                                       String description,
                                       String manual,
                                       String context,
                                       String sourceCategory) {
        String partner = normalize(partnerName);
        String offerName = normalize(benefitName);
        String manualSummary = normalize(manualSummary(manual));

        boolean financialCardSpend = isFinancialCardSpend(partnerName, benefitName, description, manual, context);
        String businessType = financialCardSpend ? "OTHER" : classifyOfferPurpose(offerName);
        if (!financialCardSpend) {
            if (businessType.equals("OTHER")) {
                businessType = classifyOfferPurpose(partner);
            }
            // This exact provider is a counseling center; its broad source category is "교육".
            if (businessType.equals("OTHER") && partner.equals(normalize("허그맘허그인"))) {
                businessType = "COUNSELING";
            }
            if (businessType.equals("OTHER") && isKnownBeverageChain(partner + offerName)) {
                businessType = "BEVERAGE_CAFE";
            }
            if (businessType.equals("OTHER") && isConvenienceChain(partner)) {
                businessType = "CONVENIENCE_STORE";
            }
            if (businessType.equals("OTHER")) {
                businessType = classifySpecificSource(normalize(category));
            }
            if (businessType.equals("OTHER")) {
                businessType = classifySpecificSource(normalize(sourceCategory));
            }
            if (businessType.equals("OTHER")) {
                businessType = classifyDescriptionPurpose(description);
            }
            if (businessType.equals("OTHER")) {
                businessType = classifySource(normalize(category));
            }
            if (businessType.equals("OTHER")) {
                businessType = classifySource(normalize(sourceCategory));
            }
            if (businessType.equals("OTHER")) {
                businessType = classifyManualPurpose(manualSummary);
            }
            // Exact canonical identity fills gaps only after explicit offer/source evidence.
            if (businessType.equals("OTHER")) {
                businessType = AUDITED_PARTNER_TYPES.getOrDefault(partner, "OTHER");
            }
            // Delivery, subscriptions, and card benefits are not store-purchase offers.
            if (businessType.equals("CONVENIENCE_STORE")
                    && (isDeliveryOrNonStoreOffer(offerName) || containsAny(offerName, "카드"))) {
                businessType = "OTHER";
            }
        }

        // Intent and exclusion labels are left empty unless source data gains explicit fields for them.
        List<String> tags = businessType.equals("OTHER") ? List.of() : List.of(businessType);
        return new BenefitRagMetadata(businessType, List.of(), List.of(), tags);
    }

    private String classifySource(String evidence) {
        if (containsAny(evidence, "키즈카페", "실내놀이터", "키즈파크", "어린이놀이터")) return "KIDS_PLAY";
        if (containsAny(evidence, "스터디카페", "독서실", "공부공간")) return "STUDY_SPACE";
        if (containsAny(evidence, "학원", "교육", "어학원", "학습지")) return "EDUCATION";
        if (containsAny(evidence, "아이스크림", "빙수", "디저트", "베이커리", "제과")) return "DESSERT";
        if (containsAny(evidence, "카페", "커피전문점")) return "BEVERAGE_CAFE";
        if (containsAny(evidence, "교육")) return "EDUCATION";
        if (containsAny(evidence, "푸드")) return "FOOD_RESTAURANT";
        if (containsAny(evidence, "엑티비티", "액티비티")) return "ATTRACTION";
        if (containsAny(evidence, "문화/여가")) return "CULTURE";
        if (containsAny(evidence, "쇼핑")) return "SHOPPING";
        if (containsAny(evidence, "영화관", "시네마", "cgv", "롯데시네마", "메가박스")) return "MOVIE_THEATER";
        if (containsAny(evidence, "전시", "미술관", "공연장", "복합문화")) return "CULTURE";
        if (containsAny(evidence, "백화점", "아울렛", "쇼핑몰")) return "SHOPPING";
        if (containsAny(evidence, "워터파크", "수영장")) return "WATER_PARK";
        if (containsAny(evidence, "편의점")) return "CONVENIENCE_STORE";
        if (containsAny(evidence, "상담", "심리", "허그맘")) return "COUNSELING";
        if (containsAny(evidence, "자동차정비", "타이어", "세차")) return "AUTO_SERVICE";
        if (containsAny(evidence, "호텔", "숙박", "리조트")) return "HOTEL";
        if (containsAny(evidence, "놀이공원", "테마파크", "루지")) return "ATTRACTION";
        if (containsAny(evidence, "식당", "레스토랑", "피자", "치킨", "버거", "고기", "회관")) return "FOOD_RESTAURANT";
        return "OTHER";
    }

    private String classifySpecificSource(String evidence) {
        if (containsAny(evidence, "키즈카페", "실내놀이터", "키즈파크", "어린이놀이터")) return "KIDS_PLAY";
        if (containsAny(evidence, "스터디카페", "독서실", "공부공간")) return "STUDY_SPACE";
        if (containsAny(evidence, "어학원", "학원", "교육", "학습지")) return "EDUCATION";
        if (containsAny(evidence, "아이스크림", "빙수", "디저트", "베이커리", "제과")) return "DESSERT";
        if (containsAny(evidence, "카페", "커피전문점")) return "BEVERAGE_CAFE";
        if (containsAny(evidence, "영화관", "시네마", "cgv", "롯데시네마", "메가박스", "씨네q")) return "MOVIE_THEATER";
        if (containsAny(evidence, "편의점")) return "CONVENIENCE_STORE";
        return "OTHER";
    }

    private String classifyOfferPurpose(String offerName) {
        if (containsAny(offerName, "키즈카페", "실내놀이터", "키즈파크")) return "KIDS_PLAY";
        if (containsAny(offerName, "스터디카페", "독서실", "공부공간")) return "STUDY_SPACE";
        if (containsAny(offerName, "학원", "교육", "어학원", "학습지")) return "EDUCATION";
        if (containsAny(offerName, "아이스크림", "빙수", "디저트", "베이커리", "제과")) return "DESSERT";
        if (containsAny(offerName, "영화관", "영화 예매", "영화 할인", "시네마", "롯데시네마", "메가박스", "cgv", "씨네q")) return "MOVIE_THEATER";
        if (containsAny(offerName, "카페", "커피전문점")) return "BEVERAGE_CAFE";
        return "OTHER";
    }

    private String classifyDescriptionPurpose(String description) {
        if (description == null || description.isBlank()) return "OTHER";
        List<String> positiveLines = description.lines()
                .map(BenefitRagMetadataClassifier::normalize)
                .filter(line -> !containsAny(line, "제외", "불가", "미적용", "적용되지않"))
                .filter(line -> !isMerchantCategoryList(line))
                .toList();
        String positiveEvidence = String.join(" ", positiveLines);
        String evidence = normalize(positiveEvidence);
        if (containsAny(evidence, "키즈카페", "실내놀이터", "키즈파크")) return "KIDS_PLAY";
        if (containsAny(evidence, "스터디카페", "독서실", "공부공간")) return "STUDY_SPACE";
        if (containsAny(evidence, "어학원", "학원수강료", "학원강의", "학습지", "인강")) return "EDUCATION";
        if (positiveLines.stream().anyMatch(line -> isExplicitDessertDescription(line))) {
            return "DESSERT";
        }
        if (positiveLines.stream().anyMatch(line -> containsAny(line, "전통차") && containsAny(line, "카페")
                && !containsAny(line, "안내", "이용방법", "사용방법", "쿠폰", "문의"))) {
            return "BEVERAGE_CAFE";
        }
        if (containsAny(evidence, "아메리카노", "커피전문점", "커피음료", "커피할인", "커피캡슐", "드립백")) {
            return "BEVERAGE_CAFE";
        }
        if (containsAny(evidence, "영화예매", "영화할인", "영화관람권", "영화관")) return "MOVIE_THEATER";
        if (containsAny(evidence, "워터파크", "워터피아", "수영장", "스파시설")) return "WATER_PARK";
        if (containsAny(evidence, "자동차정비", "엔진오일", "타이어교체", "차량정비", "세차서비스")) return "AUTO_SERVICE";
        if (containsAny(evidence, "호텔객실", "숙박시설", "리조트객실")) return "HOTEL";
        if (containsAny(evidence, "식당할인", "레스토랑할인", "식사메뉴할인")) return "FOOD_RESTAURANT";
        if (containsAny(evidence, "공연관람", "전시관람", "미술관", "박물관")) return "CULTURE";
        if (containsAny(evidence, "쇼핑몰", "면세점", "온라인몰")) return "SHOPPING";
        return "OTHER";
    }

    private boolean isExplicitDessertDescription(String line) {
        return containsAny(line, "디저트카페", "베이커리", "과자점", "제과점");
    }

    private boolean isMerchantCategoryList(String line) {
        if (containsAny(line, "대상가맹점", "적립대상", "생활친화업종", "쇼핑외영역", "쇼핑영역", "사용처")) return true;
        int categoryMentions = (int) List.of("할인점", "편의점", "제과점", "커피전문점", "패스트푸드", "면세점", "이미용")
                .stream().filter(line::contains).count();
        return categoryMentions > 1;
    }

    private boolean isFinancialCardSpend(String partnerName, String benefitName, String description,
                                         String manual, String context) {
        String identity = normalize(blank(partnerName) + " " + blank(benefitName));
        String facts = normalize(blank(description) + " " + blank(manual) + " " + blank(context));
        return containsAny(identity, "카드") && containsAny(facts, "적립", "캐시백");
    }

    private boolean isKnownBeverageChain(String evidence) {
        return KNOWN_BEVERAGE_CHAINS.stream().anyMatch(chain -> normalize(evidence).contains(normalize(chain)));
    }

    private String classifyManualPurpose(String summary) {
        if (containsAny(summary, "구독", "카드", "앱에서", "앱 내", "쿠폰 다운로드", "로그인", "결제 페이지")) {
            return "OTHER";
        }
        if (containsAny(summary, "키즈카페", "실내놀이터", "키즈파크")) return "KIDS_PLAY";
        if (containsAny(summary, "스터디카페", "독서실", "공부공간")) return "STUDY_SPACE";
        if (containsAny(summary, "어학원", "학원 수강료", "학원 강의", "학습지")) return "EDUCATION";
        if (containsAny(summary, "카페 할인", "카페 이용", "커피전문점", "아메리카노", "커피 할인")) return "BEVERAGE_CAFE";
        return "OTHER";
    }

    private boolean isDeliveryOrNonStoreOffer(String offerName) {
        return containsAny(offerName, "택배", "배송", "구독", "정기결제", "카드발급", "카드결제", "통신요금");
    }

    private boolean isConvenienceChain(String partner) {
        return containsAny(partner, "gs25", "세븐일레븐", "이마트24") || partner.equals("cu");
    }

    private String manualSummary(String manual) {
        if (manual == null || manual.isBlank()) return "";
        String normalized = manual.replace('\r', '\n');
        int boundary = normalized.length();
        for (String heading : List.of("■ 이용 방법", "■ 이용방법", "■ 유의 사항", "■ 유의사항", "이용 방법", "이용방법", "유의 사항", "유의사항",
                "쿠폰 다운로드 방법", "쿠폰 사용 방법", "다운로드 방법", "사용 방법", "구매 방법")) {
            int index = normalized.indexOf(heading);
            if (index >= 0) boundary = Math.min(boundary, index);
        }
        String lead = normalized.substring(0, boundary).replaceAll("(?s)<[^>]*>", " ").trim();
        StringBuilder facts = new StringBuilder();
        for (String line : lead.split("[\\r\\n]+|(?<=%)\\s+(?=[-①-⑩])")) {
            String fact = line.trim();
            if (fact.isBlank() || containsAny(normalize(fact), "로그인", "다운로드", "쿠폰함", "앱에서", "앱 내", "클릭", "선택", "입력", "등록 완료")) {
                continue;
            }
            if (fact.length() > 240) continue;
            if (facts.length() > 0) facts.append(' ');
            facts.append(fact);
            if (facts.length() >= 600) break;
        }
        return facts.toString();
    }

    private String blank(String value) {
        return value == null ? "" : value;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(normalize(needle))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").trim();
    }

    private static Map<String, String> normalizedPartnerTypes(Map<String, String> partnerTypes) {
        return partnerTypes.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> normalize(entry.getKey()), Map.Entry::getValue));
    }
}
