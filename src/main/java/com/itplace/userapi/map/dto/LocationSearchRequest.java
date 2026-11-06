package com.itplace.userapi.map.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record LocationSearchRequest(
        @NotNull String query,
        @Valid Center center
) {
    @AssertTrue(message = "검색어는 공백을 제외하고 2~100자여야 합니다.")
    public boolean isQueryLengthValid() {
        if (query == null) {
            return false;
        }
        int length = query.strip().length();
        return length >= 2 && length <= 100;
    }

    public record Center(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng
    ) {
    }
}
