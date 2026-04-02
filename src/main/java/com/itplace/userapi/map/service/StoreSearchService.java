package com.itplace.userapi.map.service;


public interface StoreSearchService {
    StoreSearchResult searchByKeyword(String keyword, String category);
}
