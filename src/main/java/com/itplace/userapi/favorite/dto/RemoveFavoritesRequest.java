package com.itplace.userapi.favorite.dto;

import java.util.List;
import lombok.Data;

@Data
public class RemoveFavoritesRequest {
    private Long userId;
    private List<Long> benefitIds;
}

