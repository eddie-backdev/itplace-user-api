package com.itplace.userapi.favorite.dto.request;

import java.util.List;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class RemoveFavoritesRequest {
    private Long userId;
    @NotNull
    private List<@NotNull @Positive Long> benefitIds;
}
