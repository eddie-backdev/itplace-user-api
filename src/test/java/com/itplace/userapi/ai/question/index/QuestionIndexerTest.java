package com.itplace.userapi.ai.question.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.itplace.userapi.ai.question.service.ElasticQuestionService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class QuestionIndexerTest {

    @Test
    void defaultContextDoesNotCreateOfflineSeederOrRequireItsDependencies() {
        new ApplicationContextRunner()
                .withUserConfiguration(QuestionIndexer.class)
                .run(context -> assertThat(context).doesNotHaveBean(QuestionIndexer.class));
    }

    @Test
    void explicitSeedSettingCreatesOfflineSeeder() {
        new ApplicationContextRunner()
                .withUserConfiguration(QuestionIndexer.class)
                .withBean(EmbeddingService.class, () -> mock(EmbeddingService.class))
                .withBean(ElasticQuestionService.class, () -> mock(ElasticQuestionService.class))
                .withPropertyValues("app.ai.questions.seed.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(QuestionIndexer.class));
    }

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
