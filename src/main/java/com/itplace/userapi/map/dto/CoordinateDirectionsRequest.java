package com.itplace.userapi.map.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CoordinateDirectionsRequest(
        @NotNull MapDirectionsRequest.Mode mode,
        @NotNull @Valid MapDirectionsRequest.Origin origin,
        @NotNull @Valid Destination destination
) {
    public record Destination(
            @NotNull @jakarta.validation.constraints.DecimalMin("-90.0")
            @jakarta.validation.constraints.DecimalMax("90.0") Double lat,
            @NotNull @jakarta.validation.constraints.DecimalMin("-180.0")
            @jakarta.validation.constraints.DecimalMax("180.0") Double lng,
            @Size(max = 100) String name
    ) {
    }
}
