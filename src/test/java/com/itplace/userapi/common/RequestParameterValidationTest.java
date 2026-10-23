package com.itplace.userapi.common;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.itplace.userapi.ai.question.controller.QuestionSearchController;
import com.itplace.userapi.ai.question.service.QuestionRecommendationService;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.log.controller.LogController;
import com.itplace.userapi.log.service.LogService;
import com.itplace.userapi.recommend.controller.RecommendationController;
import com.itplace.userapi.recommend.service.RecommendationServiceImpl;
import com.itplace.userapi.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class RequestParameterValidationTest {

    @Test
    void questionRecommendationRejectsOutOfRangeCoordinates() throws Exception {
        QuestionSearchController controller = new QuestionSearchController(
                mock(EmbeddingService.class),
                mock(ElasticsearchClient.class),
                mock(QuestionRecommendationService.class),
                mock(UserRepository.class)
        );

        mockMvc(controller)
                .perform(get("/api/v1/questions/recommend")
                        .param("question", "근처 카페를 추천해줘")
                        .param("lat", "91")
                        .param("lng", "181"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void legacyQuestionMemoryRejectsQwenDimensionsBeforeCallingElasticsearch() {
        EmbeddingService embeddings = mock(EmbeddingService.class);
        QuestionSearchController controller = new QuestionSearchController(
                embeddings,
                mock(ElasticsearchClient.class),
                mock(QuestionRecommendationService.class),
                mock(UserRepository.class)
        );
        ReflectionTestUtils.setField(controller, "embeddingProvider", "ollama");
        ReflectionTestUtils.setField(controller, "embeddingDimensions", 2560);

        assertThatThrownBy(() -> controller.searchSimilarQuestion("영화 할인"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(embeddings);
    }

    @Test
    void recommendationRejectsOutOfRangeTopK() throws Exception {
        RecommendationController controller = new RecommendationController(mock(RecommendationServiceImpl.class));

        mockMvc(controller)
                .perform(get("/api/v1/recommendations").param("topK", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    @Test
    void searchRankingRejectsOutOfRangePeriod() throws Exception {
        LogController controller = new LogController(mock(LogService.class));

        mockMvc(controller)
                .perform(get("/api/v1/partners/search-ranking").param("recentDay", "366"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETER"));
    }

    private MockMvc mockMvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .defaultRequest(get("/").accept(MediaType.APPLICATION_JSON))
                .build();
    }
}
