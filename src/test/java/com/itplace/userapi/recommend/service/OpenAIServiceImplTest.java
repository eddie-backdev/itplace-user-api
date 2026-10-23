package com.itplace.userapi.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.response.Recommendations;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class OpenAIServiceImplTest {

    @Test
    void emptyCandidatesSkipExternalCalls() {
        var client = org.mockito.Mockito.mock(org.springframework.web.reactive.function.client.WebClient.class);
        var service = new OpenAIServiceImpl(new com.fasterxml.jackson.databind.ObjectMapper(), client, null, null, null);
        assertThat(service.rerankAndExplain(null, java.util.List.of(), 5)).isEmpty();
        assertThat(service.rerankAndExplain(null, null, 5)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    @Test
    void truncateForPrompt_limitsLongTextAndNormalizesLineBreaks() {
        String text = "12345\n67890\r\nABCDE";

        String result = OpenAIServiceImpl.truncateForPrompt(text, 8, "기본값");

        assertThat(result).isEqualTo("12345 67…");
    }

    @Test
    void truncateForPrompt_usesDefaultForBlankText() {
        String result = OpenAIServiceImpl.truncateForPrompt("   ", 10, "기본값");

        assertThat(result).isEqualTo("기본값");
    }

    @Test
    void providerOutputCanOnlySelectAllowedUniquePartnersAndBackfillsCandidates() {
        List<Recommendations> result = serviceReturning(chatResponse(
                "{\"recommendations\":[{\"rank\":1,\"partnerName\":\"Allowed B\",\"reason\":\"grounded\"},"
                        + "{\"rank\":2,\"partnerName\":\"Invented\",\"reason\":\"unsupported\"},"
                        + "{\"rank\":3,\"partnerName\":\"Allowed B\",\"reason\":\"duplicate\"}]}"))
                .rerankAndExplain(
                UserFeature.builder().build(), candidates(), 3);

        assertThat(result).extracting(Recommendations::getPartnerName)
                .containsExactly("Allowed B", "Allowed A");
        assertThat(result).extracting(Recommendations::getBenefitIds)
                .containsExactly(List.of(2L), List.of(1L));
        assertThat(result.get(1).getFallbackFlags()).contains("llm_backfill");
        assertThat(result).allSatisfy(item -> assertThat(item.getPartnerName())
                .isIn("Allowed A", "Allowed B"));
    }

    @Test
    void malformedFencedAndTypeMismatchedResponsesFallBackToCandidates() {
        for (String content : List.of(
                "not-json",
                "```json\n{\"recommendations\":[]}\n```",
                "{\"recommendations\":[{\"rank\":\"first\",\"partnerName\":\"Allowed A\"}]}",
                "{\"recommendations\":[{\"rank\":{},\"partnerName\":\"Allowed A\"}]}") ) {
            List<Recommendations> result = serviceReturning(chatResponse(content))
                    .rerankAndExplain(UserFeature.builder().build(), candidates(), 2);

            assertFallbackCandidates(result);
        }
    }

    @Test
    void emptyNullAndMalformedOuterResponsesFallBackToCandidates() {
        for (String body : List.of("{}", "{\"choices\":[]}", "null", "not-json")) {
            assertFallbackCandidates(serviceReturning(body)
                    .rerankAndExplain(UserFeature.builder().build(), candidates(), 2));
        }

        assertFallbackCandidates(serviceReturning(200, "")
                .rerankAndExplain(UserFeature.builder().build(), candidates(), 2));
    }

    @Test
    void providerNetworkFailureFallsBackWithoutInventingCandidates() {
        OpenAIServiceImpl service = serviceWith(exchange -> Mono.error(new IllegalStateException("offline")));

        assertFallbackCandidates(service.rerankAndExplain(
                UserFeature.builder().build(), candidates(), 2));
    }

    private static void assertFallbackCandidates(List<Recommendations> result) {
        assertThat(result).extracting(Recommendations::getPartnerName)
                .containsExactly("Allowed A", "Allowed B");
        assertThat(result).extracting(Recommendations::getBenefitIds)
                .containsExactly(List.of(1L), List.of(2L));
        assertThat(result).allSatisfy(item -> assertThat(item.getFallbackFlags())
                .contains("openai_fallback"));
    }

    private static OpenAIServiceImpl serviceReturning(String body) {
        return serviceReturning(200, body);
    }

    private static OpenAIServiceImpl serviceReturning(int status, String body) {
        return serviceWith(request -> Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                .header("Content-Type", "application/json")
                .body(body)
                .build()));
    }

    private static OpenAIServiceImpl serviceWith(ExchangeFunction exchangeFunction) {
        WebClient client = WebClient.builder().exchangeFunction(exchangeFunction).build();
        OpenAIServiceImpl service = new OpenAIServiceImpl(new ObjectMapper(), client, null, null, null);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "baseUrl", "http://provider.invalid");
        ReflectionTestUtils.setField(service, "model", "test-model");
        return service;
    }

    private static String chatResponse(String content) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of("choices", List.of(
                    java.util.Map.of("message", java.util.Map.of("role", "assistant", "content", content)))));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static List<Candidate> candidates() {
        return List.of(
                Candidate.builder().benefitId(1L).partnerName("Allowed A").context("A context").build(),
                Candidate.builder().benefitId(2L).partnerName("Allowed B").context("B context").build());
    }
}
