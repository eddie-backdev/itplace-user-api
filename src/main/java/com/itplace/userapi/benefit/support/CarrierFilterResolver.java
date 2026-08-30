package com.itplace.userapi.benefit.support;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.exception.InvalidEnumException;
import java.util.Arrays;
import java.util.List;

public final class CarrierFilterResolver {

    private CarrierFilterResolver() {
    }

    public static List<Carrier> resolve(Carrier carrier, List<String> carriers) {
        if (carriers == null || carriers.isEmpty()) {
            return carrier == null ? List.of() : List.of(carrier);
        }

        try {
            List<Carrier> parsedCarriers = carriers.stream()
                    .flatMap(value -> Arrays.stream(value.split(",")))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(Carrier::valueOf)
                    .distinct()
                    .toList();
            return parsedCarriers.isEmpty() && carrier != null ? List.of(carrier) : parsedCarriers;
        } catch (IllegalArgumentException exception) {
            throw new InvalidEnumException(BenefitCode.INVALID_CARRIER_FILTER);
        }
    }
}
