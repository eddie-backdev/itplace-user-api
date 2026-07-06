package com.itplace.userapi.ai.rag.service;

import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.recommend.dto.Candidate;
import java.util.List;

public interface BenefitSearchService {
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
}
