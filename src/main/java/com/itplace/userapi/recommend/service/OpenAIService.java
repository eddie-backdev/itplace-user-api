package com.itplace.userapi.recommend.service;

import com.itplace.userapi.recommend.domain.UserFeature;
import com.itplace.userapi.recommend.dto.Candidate;
import com.itplace.userapi.recommend.dto.Recommendations;
import java.util.List;

public interface OpenAIService {
    List<Candidate> vectorSearch(UserFeature uf, int topK);

    List<Recommendations> rerankAndExplain(UserFeature uf, List<Candidate> cands, int topK);

}
