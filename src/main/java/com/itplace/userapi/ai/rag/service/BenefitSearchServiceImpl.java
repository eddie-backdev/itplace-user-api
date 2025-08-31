//package com.itplace.userapi.ai.rag.service;
//
//import co.elastic.clients.elasticsearch.ElasticsearchClient;
//import co.elastic.clients.elasticsearch._types.KnnQuery;
//import co.elastic.clients.elasticsearch.core.SearchRequest;
//import co.elastic.clients.elasticsearch.core.SearchResponse;
//import co.elastic.clients.json.JsonData;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.itplace.userapi.benefit.entity.Benefit;
//import com.itplace.userapi.benefit.entity.enums.Grade;
//import com.itplace.userapi.benefit.repository.BenefitRepository;
//import com.itplace.userapi.recommend.dto.Candidate;
//import java.io.IOException;
//import java.util.List;
//import lombok.RequiredArgsConstructor;
//import org.springframework.stereotype.Service;
//
//@Service
//@RequiredArgsConstructor
//public class BenefitSearchServiceImpl implements BenefitSearchService {
//
//    private final ElasticsearchClient esClient;
//    private final BenefitRepository benefitRepository;
//
//    public List<Candidate> queryVector(Grade grade, float[] userEmbedding, int CandidateSize) {
//        try {
//            KnnQuery knnQuery = KnnQuery.of(k -> k
//                    .field("embedding")
//                    .k(CandidateSize)
//                    .numCandidates(100)
//                    .queryVector(userEmbedding)
//            );
//
//            SearchRequest request = SearchRequest.of(s -> s
//                    .index("benefit")
//                    .knn(knnQuery)
//                    .size(20) //default 10
//            );
//
//            SearchResponse<JsonData> response = esClient.search(request, JsonData.class);
//            return response.hits().hits().stream()
//                    .map(hit -> {
//                        JsonNode node = hit.source().to(JsonNode.class);
//                        Long benefitId = node.get("benefitId").asLong();
//
//                        // DB에서 상세 정보 조회
//                        Benefit benefit = benefitRepository.findById(benefitId)
//                                .orElseThrow(() -> new RuntimeException("혜택 정보가 존재하지 않습니다: " + benefitId));
//
//                        // context 추출
//                        String context = benefit.getTierBenefits().stream()
//                                .filter(tb -> tb.getGrade() == grade)
//                                .map(tb -> tb.getContext() != null ? tb.getContext() : "")
//                                .findFirst()
//                                .orElse("등급별 혜택 정보 없음");
//
//                        return Candidate.builder()
//                                .benefitId(benefitId)
//                                .partnerId(node.get("benefitId").asLong())
//                                .benefitName(node.get("benefitName").asText())
//                                .partnerName(node.get("partnerName").asText())
//                                .category(node.get("category").asText())
//                                .description(node.get("description").asText())
//                                .context(context)
//                                .build();
//                    })
//                    .toList();
//        } catch (IOException e) {
//            throw new RuntimeException("ES 유사도 검색 실패", e);
//        }
//    }
//
//
//}
//
