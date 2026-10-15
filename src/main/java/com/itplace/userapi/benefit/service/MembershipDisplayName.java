package com.itplace.userapi.benefit.service;

import com.itplace.userapi.benefit.entity.BenefitCarrierPolicy;
import com.itplace.userapi.benefit.entity.enums.MembershipOptionType;
import java.util.regex.Pattern;

/** Resolves presentation names only for the crawler's generated membership titles. */
public final class MembershipDisplayName {
    private static final Pattern GENERATED_SUFFIX = Pattern.compile(
            "^(.*?)\\s+(?:할인형|적립형|(?:SKT|KT|LGU\\+)\\s+멤버십)$");

    private MembershipDisplayName() {}

    public static String commonName(String rawName, String partnerName) {
        if (rawName == null || partnerName == null || partnerName.isBlank()) return rawName;
        var generated = GENERATED_SUFFIX.matcher(rawName.trim());
        if (!generated.matches()) return rawName;
        String rawBrand = generated.group(1).trim();
        String displayBrand = rawBrand.equalsIgnoreCase(partnerName.trim()) ? partnerName.trim() : rawBrand;
        return displayBrand + " 멤버십 혜택";
    }

    public static String fullName(BenefitCarrierPolicy policy, String rawName, String partnerName) {
        String common = commonName(rawName, partnerName);
        MembershipOptionType option = policy == null ? null : policy.getMembershipOptionType();
        String label = option == null ? null : option.getDisplayLabel();
        return label == null || partnerName == null || common == null
                || rawName == null || common.equals(rawName.trim())
                ? common
                : common + " · " + label;
    }

    public static String recommendationName(String rawName, String partnerName, String carrier) {
        String common = commonName(rawName, partnerName);
        if (rawName == null || common == null || common.equals(rawName.trim())
                || carrier == null || !carrier.equalsIgnoreCase("SKT")) return common;
        if (rawName.endsWith(" 할인형")) return common + " · 할인형";
        if (rawName.endsWith(" 적립형")) return common + " · 적립형";
        return common;
    }
}
