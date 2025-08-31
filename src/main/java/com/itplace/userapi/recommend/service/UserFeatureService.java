package com.itplace.userapi.recommend.service;

import com.itplace.userapi.recommend.domain.UserFeature;

public interface UserFeatureService {
    UserFeature loadUserFeature(Long userId);

    float[] embedUserFeatures(UserFeature uf);

    String getUserEmbeddingContext(UserFeature uf);

}
