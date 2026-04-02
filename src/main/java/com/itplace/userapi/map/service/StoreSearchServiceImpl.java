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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StoreSearchServiceImpl implements StoreSearchService {

    private final ElasticsearchClient esClient;

    @Override
    public StoreSearchResult searchByKeyword(String keyword, String category) {
        try {
            // 1차: partnerName(브랜드명)에 매칭되는 매장 — 높은 우선순위
            List<Long> brandMatchIds = search(buildQuery("partnerName", keyword, category), 100);

            // 2차: storeName, business에 매칭되는 매장 — 브랜드 매치 제외
            Set<Long> brandSet = new HashSet<>(brandMatchIds);
            List<Long> nameMatchIds = search(buildMultiQuery(List.of("storeName", "business"), keyword, category), 200)
                    .stream()
                    .filter(id -> !brandSet.contains(id))
                    .toList();

            return new StoreSearchResult(brandMatchIds, nameMatchIds);
        } catch (IOException e) {
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
