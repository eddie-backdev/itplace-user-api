package com.itplace.userapi.ai.rag.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import java.io.IOException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElasticServiceImplTest {
    @Mock private ElasticsearchClient client;
    @Mock private ElasticsearchIndicesClient indices;
    @InjectMocks private ElasticServiceImpl service;

    @Test
    void existingStoreIndexIsLeftAlone() throws Exception {
        when(client.indices()).thenReturn(indices);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(true));

        service.createStoreIndexIfNotExists();

        verify(indices, never()).create(any(Function.class));
    }

    @Test
    void indexInitializationFailureIsWrappedWithCause() throws Exception {
        when(client.indices()).thenReturn(indices);
        RuntimeException transportFailure = new RuntimeException("unavailable");
        when(indices.exists(any(Function.class))).thenThrow(transportFailure);

        assertThatThrownBy(() -> service.createIndexIfNotExists("benefit"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not create index")
                .hasCause(transportFailure);
    }

    @Test
    void existsByIdWrapsIoFailure() throws Exception {
        IOException transportFailure = new IOException("unavailable");
        when(client.exists(any(Function.class))).thenThrow(transportFailure);

        assertThatThrownBy(() -> service.existsById("benefit", "doc-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not check if index exists")
                .hasCause(transportFailure);
    }
}
