package com.itplace.userapi.ai.question.service;

public interface ElasticQuestionService {
    void createQuestionIndexIfNotExists(String indexName);

    boolean existsById(String indexName, String id);

    void saveQuestion(String indexName, String id, String question, String category, java.util.List<Float> embedding);
}
