package com.itplace.userapi.ai.question.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElasticQuestionServiceImplTest {
    @Mock private ElasticsearchClient client;
    @Mock private ElasticsearchIndicesClient indices;
    @InjectMocks private ElasticQuestionServiceImpl service;

    @Test
    void existingQuestionIndexDoesNotIssueCreateRequest() throws Exception {
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(true));

        service.createQuestionIndexIfNotExists("questions");

        verify(indices, never()).create(any(Function.class));
    }

    @Test
    void indexInitializationFailureIsWrappedWithCause() throws Exception {
        when(client.indices()).thenReturn(indices);
        RuntimeException transportFailure = new RuntimeException("unavailable");
        when(indices.exists(any(Function.class))).thenThrow(transportFailure);

        assertThatThrownBy(() -> service.createQuestionIndexIfNotExists("questions"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not create question index")
                .hasCause(transportFailure);
    }

    @Test
    void existsByIdWrapsIoFailure() throws Exception {
        IOException transportFailure = new IOException("unavailable");
        when(client.exists(any(Function.class))).thenThrow(transportFailure);

        assertThatThrownBy(() -> service.existsById("questions", "q-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not check if document exists")
                .hasCause(transportFailure);
    }

    @Test
    void countDocumentsWrapsIoFailure() throws Exception {
        IOException transportFailure = new IOException("count unavailable");
        when(client.count(any(Function.class))).thenThrow(transportFailure);

        assertThatThrownBy(() -> service.countDocuments("questions"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not count question documents")
                .hasCause(transportFailure);
    }

    @Test
    void saveQuestionWrapsIoFailureWithoutClaimingSuccess() throws Exception {
        IOException transportFailure = new IOException("write unavailable");
        when(client.index(any(Function.class))).thenThrow(transportFailure);

        assertThatThrownBy(() -> service.saveQuestion("questions", "q-1", "질문", "카페", List.of(0.1f)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to save question document")
                .hasCause(transportFailure);
    }
}
