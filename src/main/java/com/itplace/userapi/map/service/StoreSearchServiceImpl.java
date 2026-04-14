package com.itplace.userapi.map.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoreSearchServiceImpl implements StoreSearchService {

    private final ElasticsearchClient esClient;
    private static final ExecutorService ES_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public StoreSearchResult searchByKeyword(String keyword, String category) {
        // 브랜드 검색과 매장명 검색을 병렬로 실행
        CompletableFuture<List<Long>> brandFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return search(buildQuery("partnerName", keyword, category), 100);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, ES_EXECUTOR);

        CompletableFuture<List<Long>> nameFuture = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return search(buildMultiQuery(List.of("storeName", "business"), keyword, category), 200);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }, ES_EXECUTOR);

        try {
            List<Long> brandMatchIds = brandFuture.get();
            Set<Long> brandSet = new HashSet<>(brandMatchIds);
            List<Long> nameMatchIds = nameFuture.get().stream()
                    .filter(id -> !brandSet.contains(id))
                    .toList();
            return new StoreSearchResult(brandMatchIds, nameMatchIds);
        } catch (ExecutionException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("매장 ES 검색 실패", e);
        }
    }

    private Query buildQuery(String field, String keyword, String category) {
        Query matchQuery = Query.of(q -> q.match(m -> m.field(field).query(keyword)));
        if (category == null) return matchQuery;
        return Query.of(q -> q.bool(b -> b
                .must(matchQuery)
                .filter(f -> f.term(t -> t.field("category").value(category)))
        ));
    }

    private Query buildMultiQuery(List<String> fields, String keyword, String category) {
        Query matchQuery = Query.of(q -> q.multiMatch(mm -> mm.query(keyword).fields(fields)));
        if (category == null) return matchQuery;
        return Query.of(q -> q.bool(b -> b
                .must(matchQuery)
                .filter(f -> f.term(t -> t.field("category").value(category)))
        ));
    }

    private List<Long> search(Query query, int size) throws IOException {
        SearchRequest request = SearchRequest.of(s -> s.index("store").query(query).size(size));
        SearchResponse<JsonData> response = esClient.search(request, JsonData.class);
        return response.hits().hits().stream()
                .map(hit -> hit.source().to(JsonNode.class).get("storeId").asLong())
                .toList();
    }
}
