package com.itplace.userapi.map.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.MsearchRequest;
import co.elastic.clients.elasticsearch.core.MsearchResponse;
import co.elastic.clients.elasticsearch.core.msearch.MultiSearchResponseItem;
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
        MsearchRequest request = MsearchRequest.of(m -> m.index("store")
                .searches(item -> item.header(h -> h).body(b -> b.query(buildQuery("partnerName", keyword, category)).size(100)))
                .searches(item -> item.header(h -> h).body(b -> b.query(buildMultiQuery(List.of("storeName", "business"), keyword, category)).size(200))));
        try {
            MsearchResponse<JsonData> response = esClient.msearch(request, JsonData.class);
            List<Long> brandMatchIds = storeIds(response.responses().get(0));
            Set<Long> brandSet = new HashSet<>(brandMatchIds);
            List<Long> nameMatchIds = storeIds(response.responses().get(1)).stream()
                    .filter(id -> !brandSet.contains(id)).toList();
            return new StoreSearchResult(brandMatchIds, nameMatchIds);
        } catch (IOException e) {
            throw new IllegalStateException("매장 ES 검색 실패", e);
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

    private List<Long> storeIds(MultiSearchResponseItem<JsonData> response) {
        if (response.isFailure()) {
            throw new IllegalStateException("매장 ES 검색 실패: " + response.failure().error().type());
        }
        if (response.result().timedOut()) {
            throw new IllegalStateException("매장 ES 검색 시간 초과");
        }
        return response.result().hits().hits().stream()
                .map(hit -> hit.source().to(JsonNode.class).get("storeId").asLong()).distinct().toList();
    }
}
