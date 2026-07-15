package com.itplace.userapi.map.service;

import com.itplace.userapi.map.entity.Store;
import java.util.Locale;
import java.util.Set;

public final class StorePartnerBusinessPolicy {

    private static final Set<String> STORAGE_ONLY_PARTNERS = Set.of(
            "다락",
            "미니창고다락"
    );

    private StorePartnerBusinessPolicy() {
    }

    public static boolean matches(Store store) {
        if (store == null || store.getPartner() == null) {
            return false;
        }
        return matches(store.getPartner().getPartnerName(), store.getBusiness());
    }

    public static boolean matches(String partnerName, String business) {
        if (!STORAGE_ONLY_PARTNERS.contains(normalize(partnerName))) {
            return true;
        }

        String normalizedBusiness = normalize(business);
        return normalizedBusiness.contains("보관") || normalizedBusiness.contains("저장");
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z0-9]+", "");
    }
}
