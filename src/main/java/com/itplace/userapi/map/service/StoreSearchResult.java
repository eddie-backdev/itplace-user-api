package com.itplace.userapi.map.service;

import java.util.List;

public record StoreSearchResult(List<Long> brandMatchIds, List<Long> nameMatchIds) {
    public boolean isEmpty() {
        return brandMatchIds.isEmpty() && nameMatchIds.isEmpty();
    }
}
