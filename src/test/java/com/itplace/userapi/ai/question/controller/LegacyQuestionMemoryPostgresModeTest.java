package com.itplace.userapi.ai.question.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.itplace.userapi.ai.question.dto.request.QuestionSaveRequest;
import com.itplace.userapi.ai.question.service.ElasticQuestionService;
import com.itplace.userapi.ai.question.service.QuestionRecommendationService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import co.elastic.clients.elasticsearch.ElasticsearchClient;

class LegacyQuestionMemoryPostgresModeTest {
    @Test
    void legacySaveDoesNotEmbedOrWriteToEsInPostgresMode() {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        ElasticQuestionService elasticQuestions = mock(ElasticQuestionService.class);
        QuestionController controller = new QuestionController(embeddings, elasticQuestions);
        ReflectionTestUtils.setField(controller, "searchProvider", "postgres");
        QuestionSaveRequest request = new QuestionSaveRequest();
        request.setQuestion("카페 혜택");
        request.setCategory("카페");

        assertThatThrownBy(() -> controller.saveQuestion(request))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unavailable with PostgreSQL search");
        verify(embeddings, never()).embed("카페 혜택");
        verify(elasticQuestions, never()).saveQuestion(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void legacySearchDoesNotEmbedOrQueryEsInPostgresMode() {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        ElasticsearchClient elasticsearch = mock(ElasticsearchClient.class);
        QuestionSearchController controller = new QuestionSearchController(embeddings, elasticsearch,
                mock(QuestionRecommendationService.class), mock(UserRepository.class));
        ReflectionTestUtils.setField(controller, "searchProvider", "postgres");

        assertThatThrownBy(() -> controller.searchSimilarQuestion("카페 혜택"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unavailable with PostgreSQL search");
        verify(embeddings, never()).embed("카페 혜택");
        verifyNoInteractions(elasticsearch);
    }
}
