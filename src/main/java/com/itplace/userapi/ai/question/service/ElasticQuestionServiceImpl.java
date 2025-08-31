package com.itplace.userapi.ai.question.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * QuestionIndexer에 필요한 코드
 */
@Service
@RequiredArgsConstructor
public class ElasticQuestionServiceImpl implements ElasticQuestionService {
    private final ElasticsearchClient esClient;

    /**
     * question_embeddings 인덱스가 없으면 생성합니다. - 필드: question (text), category (keyword), embedding (dense_vector)
     */
    public void createQuestionIndexIfNotExists(String indexName) {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();

            if (!exists) {
                esClient.indices().create(c -> c
                        .index(indexName)
                        .mappings(m -> m
                                .properties("question", p -> p.text(t -> t))
                                .properties("category", p -> p.keyword(k -> k))
                                .properties("embedding", p -> p.denseVector(dv -> dv
                                        .dims(1536)
                                        .index(true)
                                        .similarity("cosine")
                                ))
                        )
                );
                System.out.println("Created index: " + indexName);
            } else {
                System.out.println("Index already exists: " + indexName);
            }

        } catch (Exception e) {
            throw new IllegalStateException("Could not create question index", e);
        }
    }

    /**
     * 주어진 ID가 해당 인덱스에 존재하는지 확인
     */
    public boolean existsById(String indexName, String id) {
        try {
            return esClient.exists(e -> e.index(indexName).id(id)).value();
        } catch (IOException e) {
            throw new IllegalStateException("Could not check if document exists", e);
        }
    }

    /**
     * 질문 문서 단일 저장
     */
    public void saveQuestion(String indexName, String id, String question, String category,
                             java.util.List<Float> embedding) {
        try {
            Map<String, Object> doc = Map.of(
                    "question", question,
                    "category", category,
                    "embedding", embedding
            );

            esClient.index(i -> i
                    .index(indexName)
                    .id(id)
                    .document(doc)
            );

        } catch (IOException e) {
            throw new IllegalStateException("Failed to save question document", e);
        }
    }
}
