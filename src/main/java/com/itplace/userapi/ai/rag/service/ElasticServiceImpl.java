package com.itplace.userapi.ai.rag.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ElasticServiceImpl implements ElasticService {

    private final ElasticsearchClient esClient;

    public void createIndexIfNotExists(String indexName) {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(indexName)).value();
            if (!exists) {
                esClient.indices().create(c -> c
                        .index(indexName)
                        .mappings(m -> m
                                .properties("embedding", p -> p.denseVector(dv -> dv
                                        .dims(1536)
                                        .index(true)
                                        .similarity("cosine")
                                ))
                                .properties("documentId", p -> p.keyword(k -> k))
                                .properties("benefitId", p -> p.keyword(k -> k))
                                .properties("partnerId", p -> p.keyword(k -> k))
                                .properties("policyId", p -> p.keyword(k -> k))
                                .properties("tierBenefitId", p -> p.keyword(k -> k))
                                .properties("benefitName", p -> p.text(t -> t))
                                .properties("carrierBenefitName", p -> p.text(t -> t))
                                .properties("partnerName", p -> p.text(t -> t))
                                .properties("category", p -> p.keyword(k -> k))
                                .properties("mainCategory", p -> p.keyword(k -> k))
                                .properties("carrier", p -> p.keyword(k -> k))
                                .properties("grade", p -> p.keyword(k -> k))
                                .properties("isAllGrade", p -> p.boolean_(b -> b))
                                .properties("usageType", p -> p.keyword(k -> k))
                                .properties("benefitType", p -> p.keyword(k -> k))
                                .properties("active", p -> p.boolean_(b -> b))
                                .properties("sourceKey", p -> p.keyword(k -> k))
                                .properties("sourceUrl", p -> p.keyword(k -> k))
                                .properties("sourceCategory", p -> p.keyword(k -> k))
                                .properties("lastCrawledAt", p -> p.date(d -> d))
                                .properties("indexedAt", p -> p.date(d -> d))
                                .properties("sourceUpdatedAt", p -> p.date(d -> d))
                                .properties("embeddingVersion", p -> p.keyword(k -> k))
                                .properties("contentHash", p -> p.keyword(k -> k))
                                .properties("syncStatus", p -> p.keyword(k -> k))
                                .properties("deletedAt", p -> p.date(d -> d))
                                .properties("description", p -> p.text(t -> t))
                                .properties("manual", p -> p.text(t -> t))
                                .properties("context", p -> p.text(t -> t))
                                .properties("tierContext", p -> p.text(t -> t))
                                .properties("discountValue", p -> p.integer(i -> i))
                                .properties("imgUrl", p -> p.text(t -> t))
                        )
                );
                log.info("Created index: {}", indexName);
            } else {
                if ("benefit".equals(indexName)) {
                    esClient.indices().putMapping(p -> p
                            .index(indexName)
                            .properties("documentId", prop -> prop.keyword(k -> k))
                            .properties("policyId", prop -> prop.keyword(k -> k))
                            .properties("tierBenefitId", prop -> prop.keyword(k -> k))
                            .properties("carrierBenefitName", prop -> prop.text(t -> t))
                            .properties("mainCategory", prop -> prop.keyword(k -> k))
                            .properties("carrier", prop -> prop.keyword(k -> k))
                            .properties("grade", prop -> prop.keyword(k -> k))
                            .properties("isAllGrade", prop -> prop.boolean_(b -> b))
                            .properties("usageType", prop -> prop.keyword(k -> k))
                            .properties("benefitType", prop -> prop.keyword(k -> k))
                            .properties("active", prop -> prop.boolean_(b -> b))
                            .properties("sourceKey", prop -> prop.keyword(k -> k))
                            .properties("sourceUrl", prop -> prop.keyword(k -> k))
                            .properties("sourceCategory", prop -> prop.keyword(k -> k))
                            .properties("lastCrawledAt", prop -> prop.date(d -> d))
                            .properties("indexedAt", prop -> prop.date(d -> d))
                            .properties("sourceUpdatedAt", prop -> prop.date(d -> d))
                            .properties("embeddingVersion", prop -> prop.keyword(k -> k))
                            .properties("contentHash", prop -> prop.keyword(k -> k))
                            .properties("syncStatus", prop -> prop.keyword(k -> k))
                            .properties("deletedAt", prop -> prop.date(d -> d))
                            .properties("manual", prop -> prop.text(t -> t))
                            .properties("tierContext", prop -> prop.text(t -> t))
                            .properties("discountValue", prop -> prop.integer(i -> i))
                    );
                    log.info("Updated benefit index mapping for carrier/grade RAG metadata: {}", indexName);
                }
                log.info("Index already exists: {}", indexName);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not create index", e);
        }
    }

    public void createStoreIndexIfNotExists() {
        try {
            boolean exists = esClient.indices().exists(e -> e.index("store")).value();
            if (!exists) {
                esClient.indices().create(c -> c
                        .index("store")
                        .settings(s -> s
                                .analysis(a -> a
                                        .analyzer("korean", an -> an
                                                .custom(cu -> cu
                                                        .tokenizer("nori_tokenizer")
                                                        .filter(List.of("nori_part_of_speech"))
                                                )
                                        )
                                )
                        )
                        .mappings(m -> m
                                .properties("storeId", p -> p.long_(l -> l))
                                .properties("storeName", p -> p.text(t -> t.analyzer("korean")))
                                .properties("business", p -> p.text(t -> t.analyzer("korean")))
                                .properties("partnerName", p -> p.text(t -> t.analyzer("korean")))
                                .properties("category", p -> p.keyword(k -> k))
                                .properties("city", p -> p.keyword(k -> k))
                                .properties("town", p -> p.keyword(k -> k))
                        )
                );
                log.info("Created store index");
            }
        } catch (Exception e) {
            throw new IllegalStateException("store 인덱스 생성 실패", e);
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
