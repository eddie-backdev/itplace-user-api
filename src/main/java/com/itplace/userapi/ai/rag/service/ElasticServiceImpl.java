package com.itplace.userapi.ai.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ElasticServiceImpl implements ElasticService {

    private final ElasticsearchClient esClient;

    public void createIndexIfNotExists(String indexName) {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            if (!exists) {
                esClient.indices().create(c -> c
                        .index(indexName)
                        .mappings(m -> m
                                .properties("embedding", p -> p.denseVector(dv -> dv.dims(1536)))
                                .properties("benefitId", p -> p.keyword(k -> k))
                                .properties("partnerId", p -> p.keyword(k -> k))
                                .properties("benefitName", p -> p.text(t -> t))
                                .properties("partnerName", p -> p.text(t -> t))
                                .properties("category", p -> p.keyword(k -> k))
                                .properties("description", p -> p.text(t -> t))
                                .properties("context", p -> p.text(t -> t))
                                .properties("imgUrl", p -> p.text(t -> t))
                        )
                );
                System.out.println("Created index: " + indexName);
            } else {
                System.out.println("Index already exists");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not create index", e);
        }
    }

    public boolean existsById(String indexName, String id) {
        try {
            return esClient.exists(e -> e.index(indexName).id(id)).value();
        } catch (IOException e) {
            throw new IllegalStateException("Could not check if index exists", e);
        }
    }
}
