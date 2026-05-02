package com.itplace.userapi.ai.question.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuestionIndexerTest {

    @Test
    void parseCsvLine_keepsCommasInsideQuotedColumns() {
        List<String> columns = QuestionIndexer.parseCsvLine("\"온천, 스파 가고 싶은데 추천 좀\",\"온천, 스파\"");

        assertThat(columns).containsExactly("온천, 스파 가고 싶은데 추천 좀", "온천, 스파");
    }

    @Test
    void parseCsvLine_keepsSimpleColumns() {
        List<String> columns = QuestionIndexer.parseCsvLine("카페 관련 장소 알려줘,카페");

        assertThat(columns).containsExactly("카페 관련 장소 알려줘", "카페");
    }
}
