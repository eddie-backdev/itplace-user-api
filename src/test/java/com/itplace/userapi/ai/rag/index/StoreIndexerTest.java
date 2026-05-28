package com.itplace.userapi.ai.rag.index;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.itplace.userapi.ai.rag.service.ElasticService;
import com.itplace.userapi.map.repository.StoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class StoreIndexerTest {

    @Mock
    private ElasticsearchClient esClient;

    @Mock
    private StoreRepository storeRepository;

    @Mock
    private ElasticService elasticService;

    @Test
    void run_doesNotIndexStoresWhenSeedIsDisabled() throws Exception {
        StoreIndexer indexer = new StoreIndexer(esClient, storeRepository, elasticService);
        ReflectionTestUtils.setField(indexer, "seedEnabled", false);

        indexer.run(null);

        verify(elasticService, never()).createStoreIndexIfNotExists();
        verify(storeRepository, never()).findAllWithPartner();
    }
}
