package com.itplace.userapi.ai.rag.service;

import com.itplace.userapi.benefit.entity.enums.Grade;
import com.itplace.userapi.recommend.dto.Candidate;
import java.util.List;

public interface BenefitSearchService {
    List<Candidate> queryVector(Grade grade, List<Float> userEmbedding, int topK);
}
