package com.itplace.userapi.benefit.support;

import static org.assertj.core.api.Assertions.assertThat;
import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.util.List;
import org.junit.jupiter.api.Test;

class BenefitPolicySelectorTest {
    @Test
    void selectsActiveRequestedCarrierAndUsageWithDeterministicOrder() {
        var inactive = policy(1L, Carrier.SKT, false, UsageType.BOTH);
        var online = policy(2L, Carrier.KT, true, UsageType.ONLINE);
        var both = policy(3L, Carrier.KT, null, UsageType.BOTH);
        var offline = policy(4L, Carrier.LGU, true, UsageType.OFFLINE);
        var policies = List.of(offline, both, inactive, online);
        assertThat(BenefitPolicySelector.eligible(policies, List.of(), null)).containsExactly(online, both, offline);
        assertThat(BenefitPolicySelector.eligible(policies, List.of(Carrier.KT), UsageType.OFFLINE)).containsExactly(both);
        assertThat(BenefitPolicySelector.eligible(policies, List.of(Carrier.SKT), null)).isEmpty();
        assertThat(BenefitPolicySelector.eligible(policies, List.of(Carrier.LGU, Carrier.KT), UsageType.OFFLINE)).containsExactly(offline, both);
    }

    private BenefitCarrierPolicy policy(Long id, Carrier carrier, Boolean active, UsageType usage) {
        return BenefitCarrierPolicy.builder().benefitCarrierPolicyId(id).carrier(carrier).active(active).usageType(usage).build();
    }
}
