package com.itplace.userapi.benefit.support;

import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.enums.Carrier;
import com.itplace.userapi.benefit.entity.enums.UsageType;
import java.util.Comparator;
import java.util.List;

public final class BenefitPolicySelector {
    private static final List<Carrier> ORDER = List.of(Carrier.SKT, Carrier.KT, Carrier.LGU);

    private BenefitPolicySelector() {}

    public static List<BenefitCarrierPolicy> eligible(List<BenefitCarrierPolicy> policies,
                                                       List<Carrier> carriers, UsageType usageType) {
        List<Carrier> order = carriers == null || carriers.isEmpty() ? ORDER : carriers;
        return policies.stream()
                .filter(policy -> !Boolean.FALSE.equals(policy.getActive()))
                .filter(policy -> carriers == null || carriers.isEmpty() || carriers.contains(policy.getCarrier()))
                .filter(policy -> usageType == null || policy.getUsageType() == usageType || policy.getUsageType() == UsageType.BOTH)
                .sorted(Comparator.comparingInt((BenefitCarrierPolicy policy) -> {
                    int index = policy.getCarrier() == null ? -1 : order.indexOf(policy.getCarrier());
                    return index < 0 ? order.size() : index;
                }).thenComparing(BenefitCarrierPolicy::getBenefitCarrierPolicyId, Comparator.nullsLast(Long::compareTo)))
                .toList();
    }
}
