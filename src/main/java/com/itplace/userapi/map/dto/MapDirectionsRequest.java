package com.itplace.userapi.map.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record MapDirectionsRequest(
        @NotNull Mode mode,
        @NotNull @Valid Origin origin
) {
    public enum Mode {
        WALK, TRANSIT, CAR
    }

    public record Origin(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lng
    ) {
    }
}
