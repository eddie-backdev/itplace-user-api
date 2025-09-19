package com.itplace.userapi.recommend.service;

import com.itplace.userapi.recommend.domain.UserFeature;
import java.util.List;

public interface UserFeatureService {
    UserFeature loadUserFeature(Long userId);

    List<Float> embedUserFeatures(UserFeature uf);

    String getUserEmbeddingContext(UserFeature uf);

}
