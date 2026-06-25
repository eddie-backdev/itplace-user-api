package com.itplace.userapi.benefit.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.fasterxml.jackson.databind.JsonNode;
import com.itplace.userapi.ai.rag.service.EmbeddingService;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.MainCategory;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenefitHybridSearchServiceImpl implements BenefitHybridSearchService {
    private static final String BENEFIT_INDEX = "benefit";
    private static final int MIN_CANDIDATE_WINDOW = 80;
    private static final int MAX_CANDIDATE_WINDOW = 500;
    private static final int RRF_K = 60;
    private static final double LEXICAL_WEIGHT = 1.15;
    private static final double VECTOR_WEIGHT = 1.0;
    private static final List<String> LEXICAL_FIELDS = List.of(
            "benefitName^4",
            "carrierBenefitName^3",
            "partnerName^3",
            "category^2",
            "description^1.4",
            "manual",
            "context^1.2",
            "tierContext^1.2",
            "onlineContext^1.3",
            "offlineContext^1.3",
            "businessType^1.2",
            "useCases^1.2",
            "tags"
    );

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;

    @Override
    public BenefitHybridSearchResult search(String keyword,
                                            MainCategory mainCategory,
                                            String category,
                                            UsageType filter,
                                            List<Carrier> carriers,
                                            Pageable pageable) {
        String normalizedKeyword = normalize(keyword)
                .orElseThrow(() -> new IllegalArgumentException("검색어가 비어 있습니다."));
        int candidateWindow = candidateWindow(pageable);
        List<Query> filters = metadataFilters(mainCategory, category, filter, carriers);
        Map<Long, RankAccumulator> ranks = new LinkedHashMap<>();
        boolean searchSucceeded = false;
        Exception lastFailure = null;

        try {
            mergeHits(ranks, lexicalSearch(normalizedKeyword, filters, candidateWindow), LEXICAL_WEIGHT);
            searchSucceeded = true;
        } catch (IOException | RuntimeException exception) {
            lastFailure = exception;
            log.warn("혜택 lexical 검색 실패: keyword={}, reason={}", normalizedKeyword, exception.getMessage());
        }

        try {
            List<Float> queryVector = embeddingService.embed(normalizedKeyword);
            mergeHits(ranks, vectorSearch(queryVector, filters, candidateWindow), VECTOR_WEIGHT);
            searchSucceeded = true;
        } catch (IOException | RuntimeException exception) {
            lastFailure = exception;
            log.warn("혜택 vector 검색 실패: keyword={}, reason={}", normalizedKeyword, exception.getMessage());
        }

        if (!searchSucceeded) {
            throw new IllegalStateException("혜택 하이브리드 검색을 사용할 수 없습니다.", lastFailure);
        }

        List<Long> rankedIds = ranks.values().stream()
                .sorted(Comparator
                        .comparingDouble(RankAccumulator::score).reversed()
                        .thenComparing(RankAccumulator::bestRank)
                        .thenComparing(RankAccumulator::benefitId))
                .map(RankAccumulator::benefitId)
                .toList();

        return toResult(rankedIds, pageable);
    }

    @Override
    public BenefitHybridSearchResult searchLexical(String keyword,
                                                   MainCategory mainCategory,
                                                   String category,
                                                   UsageType filter,
                                                   List<Carrier> carriers,
                                                   Pageable pageable) {
        String normalizedKeyword = normalize(keyword)
                .orElseThrow(() -> new IllegalArgumentException("검색어가 비어 있습니다."));
        int candidateWindow = candidateWindow(pageable);
        List<Query> filters = metadataFilters(mainCategory, category, filter, carriers);
        try {
            List<Long> rankedIds = lexicalSearch(normalizedKeyword, filters, candidateWindow).stream()
                    .map(SearchHitSnapshot::benefitId)
                    .distinct()
                    .toList();
            return toResult(rankedIds, pageable);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("혜택 lexical 검색을 사용할 수 없습니다.", exception);
        }
    }

    private BenefitHybridSearchResult toResult(List<Long> rankedIds, Pageable pageable) {
        long totalElements = rankedIds.size();
        int pageSize = Math.max(pageable.getPageSize(), 1);
        int currentPage = pageable.getPageNumber();
        int fromIndex = (int) Math.min(pageable.getOffset(), totalElements);
        int toIndex = Math.min(fromIndex + pageSize, rankedIds.size());
        List<Long> pageIds = rankedIds.subList(fromIndex, toIndex);
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / pageSize);
        boolean hasNext = toIndex < totalElements;
        return new BenefitHybridSearchResult(pageIds, totalElements, currentPage, totalPages, hasNext);
    }

    private List<SearchHitSnapshot> lexicalSearch(String keyword, List<Query> filters, int size) throws IOException {
        SearchRequest request = SearchRequest.of(s -> s
                .index(BENEFIT_INDEX)
                .query(q -> q.bool(b -> {
                    filters.forEach(b::filter);
                    return b.must(m -> m.multiMatch(mm -> mm
                            .query(keyword)
                            .fields(LEXICAL_FIELDS)
                            .fuzziness("AUTO")
                    ));
                }))
                .size(size)
        );
        return execute(request);
    }

    private List<SearchHitSnapshot> vectorSearch(List<Float> queryVector, List<Query> filters, int size) throws IOException {
        KnnQuery knnQuery = KnnQuery.of(k -> {
            KnnQuery.Builder builder = k
                    .field("embedding")
                    .k(size)
                    .numCandidates(Math.max(size * 2, MIN_CANDIDATE_WINDOW))
                    .queryVector(queryVector);
            if (!filters.isEmpty()) {
                builder.filter(filters);
            }
            return builder;
        });

        SearchRequest request = SearchRequest.of(s -> s
                .index(BENEFIT_INDEX)
                .knn(knnQuery)
                .size(size)
        );
        return execute(request);
    }

    private List<SearchHitSnapshot> execute(SearchRequest request) throws IOException {
        SearchResponse<JsonData> response = esClient.search(request, JsonData.class);
        return response.hits().hits().stream()
                .map(BenefitHybridSearchServiceImpl::toSnapshot)
                .flatMap(Optional::stream)
                .toList();
    }

    private void mergeHits(Map<Long, RankAccumulator> ranks,
                           List<SearchHitSnapshot> hits,
                           double weight) {
        for (int index = 0; index < hits.size(); index++) {
            SearchHitSnapshot hit = hits.get(index);
            int rank = index + 1;
            double rrfScore = weight / (RRF_K + rank);
            ranks.computeIfAbsent(hit.benefitId(), RankAccumulator::new)
                    .add(rank, rrfScore, hit.score());
        }
    }

    private List<Query> metadataFilters(MainCategory mainCategory, String category, UsageType filter, List<Carrier> carriers) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("active").value(true))));
        if (mainCategory != null) {
            filters.add(Query.of(q -> q.term(t -> t.field("mainCategory").value(mainCategory.name()))));
        }
        normalize(category).ifPresent(value ->
                filters.add(Query.of(q -> q.term(t -> t.field("category").value(value))))
        );
        List<Carrier> safeCarriers = carriers == null ? List.of() : carriers;
        if (!safeCarriers.isEmpty()) {
            filters.add(Query.of(q -> q.bool(b -> {
                safeCarriers.forEach(carrier ->
                        b.should(s -> s.term(t -> t.field("carrier").value(carrier.name()))));
                return b.minimumShouldMatch("1");
            })));
        }
        if (filter != null) {
            filters.add(Query.of(q -> q.bool(b -> b
                    .should(s -> s.term(t -> t.field("usageType").value(filter.name())))
                    .should(s -> s.term(t -> t.field("usageType").value(UsageType.BOTH.name())))
                    .minimumShouldMatch("1")
            )));
        }
        return filters;
    }

    private int candidateWindow(Pageable pageable) {
        long requiredWindow = pageable.getOffset() + pageable.getPageSize();
        long expandedWindow = Math.max(requiredWindow * 4, MIN_CANDIDATE_WINDOW);
        return (int) Math.min(Math.max(expandedWindow, MIN_CANDIDATE_WINDOW), MAX_CANDIDATE_WINDOW);
    }

    private static Optional<SearchHitSnapshot> toSnapshot(Hit<JsonData> hit) {
        try {
            JsonNode node = hit.source().to(JsonNode.class);
            Long benefitId = longOrNull(node, "benefitId");
            if (benefitId == null) {
                return Optional.empty();
            }
            return Optional.of(new SearchHitSnapshot(benefitId, hit.score() == null ? 0.0 : hit.score()));
        } catch (RuntimeException exception) {
            log.warn("혜택 하이브리드 검색 hit 파싱 실패: id={}, reason={}", hit.id(), exception.getMessage());
            return Optional.empty();
        }
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.longValue();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Optional<String> normalize(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    private record SearchHitSnapshot(Long benefitId, double score) {
    }

    private static final class RankAccumulator {
        private final Long benefitId;
        private double score;
        private int bestRank = Integer.MAX_VALUE;
        private double bestRawScore;

        private RankAccumulator(Long benefitId) {
            this.benefitId = benefitId;
        }

        private RankAccumulator add(int rank, double score, double rawScore) {
            this.score += score;
            if (rank < bestRank || (rank == bestRank && rawScore > bestRawScore)) {
                this.bestRank = rank;
                this.bestRawScore = rawScore;
            }
            return this;
        }

        private Long benefitId() {
            return benefitId;
        }

        private double score() {
            return score;
        }

        private int bestRank() {
            return bestRank;
        }
    }
}
