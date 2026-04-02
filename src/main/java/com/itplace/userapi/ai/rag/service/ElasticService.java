package com.itplace.userapi.ai.rag.service;

public interface ElasticService {
    void createIndexIfNotExists(String indexName);

    void createStoreIndexIfNotExists();

    boolean existsById(String indexName, String id);
}
