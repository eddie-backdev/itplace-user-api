package com.itplace.userapi.ai.question.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import org.junit.jupiter.api.Test;

class QueryIntentExtractorTest {

    private final QueryIntentExtractor extractor = new QueryIntentExtractor();

    @Test
    void extract_preservesHotCoolPlaceSignalWithoutForcingCounseling() {
        QueryIntent intent = extractor.extract("날씨가 더운데 시원하게 갈만한 곳 추천해줘", null, null, 37.5, 127.0);

        assertThat(intent.purposeKeywords()).contains("더위", "시원한 장소");
        assertThat(intent.categoryHints()).contains("카페", "빙수", "실내");
        assertThat(intent.exclusions()).contains("상담", "결혼", "육아");
        assertThat(intent.confidence()).isGreaterThanOrEqualTo(0.6);
        assertThat(intent.locationContext()).isEqualTo("KNOWN");
    }

    @Test
    void extract_infersSktVipCafeBenefitIntent() {
        QueryIntent intent = extractor.extract("SKT VIP가 카페에서 쓸 혜택 추천해줘", null, null, 0.0, 0.0);

        assertThat(intent.carrier()).isEqualTo(Carrier.SKT);
        assertThat(intent.grade()).isEqualTo(Grade.SKT_VIP);
        assertThat(intent.categoryHints()).contains("카페", "커피");
        assertThat(intent.purposeKeywords()).contains("혜택");
        assertThat(intent.locationContext()).isEqualTo("UNKNOWN");
    }

    @Test
    void extract_prefersRequestCarrierAndGradeOverTextInference() {
        QueryIntent intent = extractor.extract("VIP 영화 혜택", Carrier.KT, Grade.KT_VVIP, 37.5, 127.0);

        assertThat(intent.carrier()).isEqualTo(Carrier.KT);
        assertThat(intent.grade()).isEqualTo(Grade.KT_VVIP);
        assertThat(intent.categoryHints()).contains("영화");
    }
}
