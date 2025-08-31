package com.itplace.userapi.favorite.service;

import com.itplace.userapi.favorite.dto.FavoriteDetailResponse;
import com.itplace.userapi.favorite.dto.FavoriteResponse;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FavoriteService {
    void addFavorite(Long userId, Long benefitId);

    void removeFavorites(Long userId, List<Long> benefitIds);

    Page<FavoriteResponse> getFavorites(Long userId, String category, Pageable pageable);

    List<FavoriteResponse> searchFavorites(Long userId, String keyword, String category);

    FavoriteDetailResponse getBenefitDetail(Long benefitId);
}