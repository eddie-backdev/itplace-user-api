package com.itplace.userapi.ai.rag.service;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.recommend.dto.Candidate;
import java.util.List;

public interface BenefitSearchService {
    record ChatSearchResult(List<Candidate> candidates, boolean available) {
        public ChatSearchResult { candidates = candidates == null ? List.of() : List.copyOf(candidates); }
    }
    List<Candidate> queryVector(Carrier carrier, Grade grade, List<Float> userEmbedding, int topK);

    default List<Candidate> queryVector(Carrier carrier,
                                        Grade grade,
                                        List<Float> userEmbedding,
                                        int topK,
                                        BenefitSearchCondition condition) {
        return queryVector(carrier, grade, userEmbedding, topK);
    }

    default List<Candidate> queryHybrid(Carrier carrier,
                                        Grade grade,
                                        List<Float> userEmbedding,
                                        String queryText,
                                        int topK,
                                        BenefitSearchCondition condition) {
        return queryVector(carrier, grade, userEmbedding, topK, condition);
    }

    default ChatSearchResult queryChat(Carrier carrier, Grade grade, List<Float> embedding, String query,
                                       int topK, BenefitSearchCondition condition) {
        return new ChatSearchResult(queryHybrid(carrier, grade, embedding, query, topK, condition), true);
    }

    default List<Candidate> resolveChatCandidates(List<String> candidateKeys, Carrier carrier, Grade grade) {
        return List.of();
    }

    default List<Candidate> browseChatCandidates(Carrier carrier, Grade grade, String channel, int limit) {
        return List.of();
    }
}
