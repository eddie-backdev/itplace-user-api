package com.itplace.userapi.benefit.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itplace.userapi.benefit.BenefitCode;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.exception.InvalidEnumException;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarrierFilterResolverTest {

    @Test
    void resolvesCommaSeparatedCarriersWithoutDuplicates() {
        assertThat(CarrierFilterResolver.resolve(null, List.of("SKT,KT", "SKT")))
                .containsExactly(Carrier.SKT, Carrier.KT);
    }

    @Test
    void fallsBackToLegacyCarrierWhenMultiCarrierFilterIsEmpty() {
        assertThat(CarrierFilterResolver.resolve(Carrier.LGU, List.of("", " ")))
                .containsExactly(Carrier.LGU);
    }

    @Test
    void rejectsUnsupportedCarrier() {
        assertThatThrownBy(() -> CarrierFilterResolver.resolve(null, List.of("UNKNOWN")))
                .isInstanceOfSatisfying(InvalidEnumException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(BenefitCode.INVALID_CARRIER_FILTER));
    }
}
